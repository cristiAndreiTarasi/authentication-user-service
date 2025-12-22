package example.com.schemas

import example.com.routes.dtos.GiftCatalogEntryDto
import example.com.schemas.queries.GiftsQueries
import java.sql.Connection
import java.sql.Timestamp
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GiftsSchema(
    private val dataSource: DataSource,
) {
    suspend fun getGiftById(giftId: String): GiftCatalogEntryDto? = withContext(Dispatchers.IO) {
        val conn = dataSource.connection
        try {
            val ps = conn.prepareStatement(
                "SELECT id, name, coin_cost AS price_coins, image_url, rarity FROM gifts_catalog WHERE id = ? OR name = ? LIMIT 1"
            )
            ps.setString(1, giftId)
            ps.setString(2, giftId)
            val rs = ps.executeQuery()
            val out = if (rs.next()) {
                GiftCatalogEntryDto(
                    id = rs.getString("id"),
                    name = rs.getString("name"),
                    priceCoins = rs.getLong("price_coins"),
                    imageUrl = rs.getString("image_url"),
                    rarity = rs.getString("rarity")
                )
            } else null
            rs.close()
            ps.close()
            out
        } finally {
            try { conn.close() } catch (_: Exception) {}
        }
    }


    // Non-suspending transactional helper: caller manages conn & commit/rollback.
    // Insert gift tx and return generated id. This is idempotent variant that tries ON CONFLICT.
    fun insertGiftTxTransactional(
        conn: Connection,
        idempotencyKey: String?,
        fromUserId: Int,
        toUserId: Int,
        streamId: String,
        giftType: String,
        quantity: Int,
        coinsAmount: Long,
        recipientReceive: Long,
        platformCut: Long
    ): Long {
        val ps = conn.prepareStatement(GiftsQueries.INSERT_GIFT_TX_IDEMPOTENT, java.sql.Statement.RETURN_GENERATED_KEYS)
        if (idempotencyKey != null) ps.setObject(1, java.util.UUID.fromString(idempotencyKey))
        else ps.setNull(1, java.sql.Types.OTHER)
        ps.setInt(2, fromUserId)
        ps.setInt(3, toUserId)
        ps.setString(4, streamId)
        ps.setString(5, giftType)
        ps.setInt(6, quantity)
        ps.setLong(7, coinsAmount)
        ps.setLong(8, recipientReceive)
        ps.setLong(9, platformCut)
        ps.setTimestamp(10, Timestamp.from(java.time.Instant.now()))
        val changed = ps.executeUpdate()
        var generatedId: Long? = null
        ps.generatedKeys.use { gk ->
            if (gk.next()) {
                generatedId = gk.getLong(1)
            }
        }
        ps.close()

        // If insert did nothing (idempotency conflict), try to find existing by idempotency_key:
        if (generatedId == null && idempotencyKey != null) {
            val sel = conn.prepareStatement("SELECT id FROM gift_transactions WHERE idempotency_key = ?")
            sel.setObject(1, java.util.UUID.fromString(idempotencyKey))
            sel.executeQuery().use { rs ->
                if (rs.next()) generatedId = rs.getLong("id")
            }
            sel.close()
        }

        return generatedId ?: throw RuntimeException("Failed to insert or find gift_transactions")
    }

    fun selectUserBalanceForUpdateTransactional(conn: Connection, userId: Int): Long {
        val ps = conn.prepareStatement(GiftsQueries.SELECT_USER_FOR_UPDATE)
        ps.setInt(1, userId)
        val rs = ps.executeQuery()
        if (!rs.next()) throw IllegalArgumentException("User not found: $userId")
        val bal = rs.getLong("balance_coins")
        rs.close()
        ps.close()
        return bal
    }

    fun updateUserBalanceTransactional(conn: Connection, userId: Int, newBalance: Long) {
        val ps = conn.prepareStatement(GiftsQueries.UPDATE_USER_BALANCE)
        ps.setLong(1, newBalance)
        ps.setInt(2, userId)
        ps.executeUpdate()
        ps.close()
    }

    fun insertUserLedgerTransactional(conn: Connection, userId: Int, delta: Long, balanceAfter: Long, reason: String, giftTxId: Long) {
        val ps = conn.prepareStatement(GiftsQueries.INSERT_USER_LEDGER)
        ps.setInt(1, userId)
        ps.setLong(2, delta)
        ps.setLong(3, balanceAfter)
        ps.setString(4, reason)
        ps.setLong(5, giftTxId)
        ps.setTimestamp(6, Timestamp.from(java.time.Instant.now()))
        ps.executeUpdate()
        ps.close()
    }

    fun insertOutboxTransactional(conn: Connection, streamName: String, eventType: String, payloadJson: String) {
        val ps = conn.prepareStatement(GiftsQueries.INSERT_OUTBOX)
        ps.setString(1, streamName)
        ps.setString(2, eventType)
        ps.setString(3, payloadJson)
        ps.executeUpdate()
        ps.close()
    }

    // Non-transactional helper to fetch catalog
    suspend fun getCatalog(): List<GiftCatalogEntryDto> = withContext(Dispatchers.IO) {
        val conn = dataSource.connection
        try {
            val stmt = conn.prepareStatement(GiftsQueries.SELECT_GIFTS_CATALOG)
            val rs = stmt.executeQuery()
            val out = mutableListOf<GiftCatalogEntryDto>()
            while (rs.next()) {
                out.add(
                    GiftCatalogEntryDto(
                        id = rs.getString("id"),
                        name = rs.getString("name"),
                        priceCoins = rs.getLong("price_coins"),
                        imageUrl = rs.getString("image_url"),
                        rarity = rs.getString("rarity")
                    )
                )
            }
            rs.close()
            stmt.close()
            out
        } finally {
            try { conn.close() } catch (_: Exception) {}
        }
    }

    // helper to update gift tx status (non-transactional)
    suspend fun updateGiftTxStatus(giftTxId: Long, status: String) = withContext(Dispatchers.IO) {
        val conn = dataSource.connection
        try {
            val ps = conn.prepareStatement(GiftsQueries.UPDATE_GIFT_TX_STATUS)
            ps.setString(1, status)
            ps.setLong(2, giftTxId)
            ps.executeUpdate()
            ps.close()
        } finally {
            try { conn.close() } catch (_: Exception) {}
        }
    }
}

