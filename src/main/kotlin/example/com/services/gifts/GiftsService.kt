// GiftsService.kt
package example.com.services.gifts

import example.com.config.AppJson
import example.com.routes.dtos.OutboxEvent
import example.com.routes.dtos.SendGiftSuccessResponseDto
import example.com.schemas.GiftsSchema
import example.com.services.redis.RedisStreams
import example.com.services.redis.RedisStreams.BILLING_EVENTS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.sql.Connection
import javax.sql.DataSource
import kotlin.math.floor

class InsufficientFundsException(val required: Long, val balance: Long) : Exception("Insufficient funds")

class GiftsService(
    private val dataSource: javax.sql.DataSource,
    private val giftsSchema: GiftsSchema,
    private val platformCutPercent: Double = 0.20,
    private val json: kotlinx.serialization.json.Json = AppJson
) {

    /**
     * Create a gift transaction in a single DB transaction and enqueue outbox event for billing worker.
     *
     * Important: This method does not broadcast WebSocket events directly. The route that calls this
     * method should publish a canonical LiveEvent.Gift (initiatorId="system") for realtime UI.
     */
    suspend fun sendGift(
        idempotencyKey: String?,
        fromUserId: Int,
        toUserId: Int,
        streamId: String,
        giftType: String,
        quantity: Int,
        coinsAmount: Long,
        fromUsername: String? = null,
        toUsername: String? = null
    ): SendGiftSuccessResponseDto = withContext(Dispatchers.IO) {
        var conn: Connection? = null
        try {
            conn = dataSource.connection
            conn.autoCommit = false

            // 1) Lock sender balance (SELECT ... FOR UPDATE) via giftsSchema helper
            val senderBalance: Long = giftsSchema.selectUserBalanceForUpdateTransactional(conn, fromUserId)
            if (senderBalance < coinsAmount) {
                conn.rollback()
                throw InsufficientFundsException(required = coinsAmount, balance = senderBalance)
            }

            // 2) compute platform cut and recipient receive amount
            val platformCut = floor(coinsAmount.toDouble() * platformCutPercent).toLong()
            val recipientReceive = coinsAmount - platformCut

            // 3) insert gift transaction row (idempotent behavior should be handled by schema helper)
            //    should return existing tx id if idempotencyKey already exists for this sender
            val giftTxId: Long = giftsSchema.insertGiftTxTransactional(
                conn = conn,
                idempotencyKey = idempotencyKey,
                fromUserId = fromUserId,
                toUserId = toUserId,
                streamId = streamId,
                giftType = giftType,
                quantity = quantity,
                coinsAmount = coinsAmount,
                recipientReceive = recipientReceive,
                platformCut = platformCut
            )

            // 4) debit sender balance and insert ledger entry
            val senderNew = senderBalance - coinsAmount
            giftsSchema.updateUserBalanceTransactional(conn, fromUserId, senderNew)
            giftsSchema.insertUserLedgerTransactional(conn, fromUserId, -coinsAmount, senderNew, "gift_sent", giftTxId)

            // 5) credit recipient balance and insert ledger entry (SELECT FOR UPDATE then UPDATE)
            val recBalanceStmt = conn.prepareStatement("SELECT balance_coins FROM users WHERE id = ? FOR UPDATE")
            recBalanceStmt.setInt(1, toUserId)
            val rs = recBalanceStmt.executeQuery()
            val recipientOld = if (rs.next()) rs.getLong("balance_coins") else 0L
            rs.close()
            recBalanceStmt.close()

            val recipientNew = recipientOld + recipientReceive
            giftsSchema.updateUserBalanceTransactional(conn, toUserId, recipientNew)
            giftsSchema.insertUserLedgerTransactional(conn, toUserId, recipientReceive, recipientNew, "gift_received", giftTxId)

            // 6) build outbox payload (polymorphic OutboxEvent)
            val outboxPayload = OutboxEvent.GiftOutbox(
                gift_tx_id = giftTxId,
                idempotency_key = idempotencyKey,
                from_user_id = fromUserId,
                from_username = fromUsername,
                to_user_id = toUserId,
                to_username = toUsername,
                stream_id = streamId,
                gift_type = giftType,
                quantity = quantity,
                coins_amount = coinsAmount,
                recipient_coins = recipientReceive,
                platform_cut = platformCut,
                created_at = java.time.Instant.now().toString()
            )

            val payloadJson = json.encodeToString(OutboxEvent.serializer(), outboxPayload)

            // 7) insert outbox row for OutboxDispatcher / BillingWorker to publish
            //    use RedisStreams.BILLING_STREAM as the logical destination stream key
            giftsSchema.insertOutboxTransactional(conn, BILLING_EVENTS, "gift_sent", payloadJson)

            // 8) commit transaction
            conn.commit()

            // 9) return success DTO (balances after)
            SendGiftSuccessResponseDto(
                giftTxId = giftTxId,
                senderBalanceAfter = senderNew,
                recipientBalanceAfter = recipientNew,
                recipientCoins = recipientReceive
            )
        } catch (ife: InsufficientFundsException) {
            // rethrow to allow caller to map it to 402
            throw ife
        } catch (ex: Exception) {
            try { conn?.rollback() } catch (_: Exception) {}
            throw ex
        } finally {
            try { conn?.close() } catch (_: Exception) {}
        }
    }
}

suspend fun computeCoinsForGift(giftId: String, quantity: Int, giftsSchema: GiftsSchema): Long {
    val gift = giftsSchema.getGiftById(giftId)
        ?: throw IllegalArgumentException("Unknown giftId: $giftId")
    // assume priceCoins: Long on GiftCatalogEntryDto
    return gift.priceCoins * quantity.toLong()
}
