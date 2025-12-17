package example.com.schemas

import example.com.schemas.queries.PaymentQueries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

// server-side constant (single source of truth)
val COINS_PER_USD: BigDecimal = BigDecimal.valueOf(100L)

class PaymentSchema(private val dataSource: DataSource) {

    /**
     * Apply a successful recharge for a user.
     *
     * Idempotent when providerTransactionId is provided:
     *  - if a success row exists for that providerTransactionId, returns current balance (no double-credit).
     *
     * Returns updated balance in coins (Long).
     */
    suspend fun applyRecharge(
        userId: Int,
        provider: String,
        method: String,
        providerTransactionId: String?, // nullable - use for idempotency
        amountUsd: BigDecimal
    ): Long = dbQuery { conn ->
        val prevAuto = conn.autoCommit
        try {
            conn.autoCommit = false

            // 1) Idempotency: if providerTransactionId is supplied, check existing tx.
            if (!providerTransactionId.isNullOrBlank()) {
                conn.prepareStatement(PaymentQueries.SELECT_TX_BY_PROVIDER_ID).use { ps ->
                    ps.setString(1, providerTransactionId)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) {
                            val status = rs.getString("status")
                            if (status.equals("success", ignoreCase = true)) {
                                // already credited — return current balance
                                conn.prepareStatement(PaymentQueries.SELECT_BALANCE).use { s ->
                                    s.setInt(1, userId)
                                    s.executeQuery().use { brs ->
                                        if (brs.next()) {
                                            val current = brs.getLong("balance_coins")
                                            conn.commit()
                                            return@dbQuery current
                                        } else {
                                            conn.rollback()
                                            throw IllegalArgumentException("User not found: $userId")
                                        }
                                    }
                                }
                            }
                            // if exists but not success, proceed to credit (rare)
                        }
                    }
                }
            }

            // 2) Compute coins to add
            val coinsToAdd = usdToCoins(amountUsd)

            // 3) Update balance and RETURNING new balance
            val newBalanceCoins: Long = conn.prepareStatement(PaymentQueries.UPDATE_BALANCE_RETURNING).use { ps ->
                ps.setLong(1, coinsToAdd)
                ps.setInt(2, userId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) rs.getLong(1)
                    else {
                        conn.rollback()
                        throw IllegalArgumentException("User not found: $userId")
                    }
                }
            }

            // 4) Insert transaction record (handle unique-constraint race gracefully)
            try {
                conn.prepareStatement(PaymentQueries.INSERT_TX).use { ins ->
                    ins.setInt(1, userId)
                    ins.setString(2, provider)
                    ins.setString(3, method)
                    if (!providerTransactionId.isNullOrBlank()) ins.setString(4, providerTransactionId) else ins.setNull(4, java.sql.Types.VARCHAR)
                    ins.setBigDecimal(5, amountUsd.setScale(2, RoundingMode.HALF_UP))
                    ins.setLong(6, coinsToAdd)
                    ins.executeUpdate()
                }
            } catch (sqe: SQLException) {
                // If duplicate key on provider_transaction_id happened due to race,
                // treat it as idempotent: fetch current balance and return it.
                // SQLState starting with '23' indicates constraint violation in PG.
                if (sqe.sqlState?.startsWith("23") == true) {
                    // fetch current balance and use it as authoritative
                    conn.prepareStatement(PaymentQueries.SELECT_BALANCE).use { s ->
                        s.setInt(1, userId)
                        s.executeQuery().use { brs ->
                            if (brs.next()) {
                                val current = brs.getLong("balance_coins")
                                conn.commit()
                                return@dbQuery current
                            } else {
                                conn.rollback()
                                throw IllegalArgumentException("User not found: $userId")
                            }
                        }
                    }
                } else {
                    throw sqe
                }
            }

            conn.commit()
            newBalanceCoins
        } catch (ex: Exception) {
            try { conn.rollback() } catch (_: Exception) {}
            throw ex
        } finally {
            try { conn.autoCommit = prevAuto } catch (_: Exception) {}
        }
    }

    /**
     * Convert USD -> coins (BigDecimal -> Long). Rounds HALF_UP.
     */
    private fun usdToCoins(amountUsd: BigDecimal): Long {
        return amountUsd.multiply(COINS_PER_USD)
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()
    }

    // ----------------------
    // dbQuery helper (same pattern as UserSchema)
    // ----------------------
    private suspend fun <T> dbQuery(block: suspend (Connection) -> T): T = withContext(Dispatchers.IO) {
        var conn: Connection? = null
        try {
            conn = dataSource.connection
            block(conn)
        } catch (e: java.sql.SQLException) {
            throw RuntimeException("Database query failed: ${e.message}", e)
        } finally {
            try { conn?.close() } catch (_: Exception) {}
        }
    }
}
