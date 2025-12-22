package example.com.services.workers

import example.com.config.AppJson
import example.com.config.awaitFuture
import example.com.routes.dtos.LiveEvent
import example.com.schemas.GiftsSchema
import example.com.services.redis.RedisService
import example.com.services.redis.RedisStreams
import example.com.services.redis.ShardedRedisService
import io.lettuce.core.Consumer
import io.lettuce.core.StreamMessage
import io.lettuce.core.XReadArgs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import javax.sql.DataSource

@Serializable
data class BillingOutboxPayloadLite(
    val type: String,
    val gift_tx_id: Long,
    val from_user_id: Int,
    val to_user_id: Int,
    val stream_id: String,
    val gift_type: String,
    val quantity: Int,
    val coins_amount: Long,
    val recipient_coins: Long,
    val platform_cut: Long,
    val created_at: String
)

class BillingWorker(
    private val billingRedis: RedisService,
    private val shardedRedisService: ShardedRedisService,
    private val dataSource: DataSource,
    private val consumerGroup: String = "billing_group",
    private val consumerId: String = "billing-consumer-${System.getenv("HOSTNAME") ?: "local"}"
) {
    private val json = AppJson

    suspend fun start() {
        println("💰 BillingWorkerV2 starting")
        try {
            billingRedis.createConsumerGroupIfNotExists(RedisStreams.BILLING_EVENTS, consumerGroup)
        } catch (e: Exception) {
            println("WARN: billing worker group create error: ${e.message}")
        }

        while (true) {
            try {
                val messages = billingRedis.consumerCommands.xreadgroup(
                    Consumer.from(consumerGroup, consumerId),
                    XReadArgs.Builder.block(2000).count(50),
                    XReadArgs.StreamOffset.from(RedisStreams.BILLING_EVENTS, ">")
                ).awaitFuture()

                if (messages.isNullOrEmpty()) {
                    delay(100)
                    continue
                }

                for (msg in messages) {
                    try {
                        processMsg(msg)
                    } catch (ex: Exception) {
                        println("ERROR: BillingWorkerV2 failed to process ${msg.id}: ${ex.message}")
                        // leave in PEL; optionally xack after moving to DLQ
                    }
                }
            } catch (e: Exception) {
                println("ERROR: BillingWorkerV2 main loop: ${e.message}")
                delay(1000)
            }
        }
    }

    private suspend fun processMsg(msg: StreamMessage<String, String>) {
        val raw = msg.body["event"] ?: run {
            // ack malformed
            billingRedis.consumerCommands.xack(RedisStreams.BILLING_EVENTS, consumerGroup, msg.id).awaitFuture()
            return
        }

        val evt = try {
            json.decodeFromString<BillingOutboxPayloadLite>(raw)
        } catch (e: Exception) {
            println("WARN: BillingWorkerV2 malformed payload: ${e.message}")
            billingRedis.consumerCommands.xack(RedisStreams.BILLING_EVENTS, consumerGroup, msg.id).awaitFuture()
            return
        }

        // idempotency: check gift_transactions.status
        if (isGiftProcessed(evt.gift_tx_id)) {
            billingRedis.consumerCommands.xack(RedisStreams.BILLING_EVENTS, consumerGroup, msg.id).awaitFuture()
            return
        }

        // 1) update billing redis counters / leaderboard
        try {
            // leaderboard: top gifters per stream
            billingRedis.producerCommands.zincrby("leaderboard:gifters:${evt.stream_id}", evt.coins_amount.toDouble(), evt.from_user_id.toString()).awaitFuture()
            // room counters
            billingRedis.producerCommands.incrby("${RedisStreams.ROOM_COUNTERS_PREFIX}${evt.stream_id}:gifts_total", evt.coins_amount).awaitFuture()
        } catch (e: Exception) {
            println("WARN: BillingWorkerV2 failed redis counters: ${e.message}")
            // leave for retry
            return
        }

        // 2) canonical broadcast -> publish to sessions (cross-instance)
        val canonicalPayload = buildRoomGiftPayload(evt)
        try {
            shardedRedisService.publishToRoom(evt.stream_id, canonicalPayload)
        } catch (e: Exception) {
            println("WARN: billing publishToRoom failed: ${e.message}")
            // leave for retry
            return
        }

        // 3) push social event (optional) for Neo4j
        try {
            val socialEvent = mapOf(
                "type" to "gift_sent",
                "gift_tx_id" to evt.gift_tx_id.toString(),
                "actorId" to evt.from_user_id.toString(),
                "targetId" to evt.to_user_id.toString(),
                "coins" to evt.coins_amount.toString(),
                "ts" to evt.created_at
            )

            shardedRedisService.socialRedis.producerCommands.xadd(
                RedisStreams.BILLING_EVENTS,
                mapOf("event" to json.encodeToString(socialEvent))
            ).awaitFuture()
        } catch (e: Exception) {
            println("WARN: BillingWorkerV2 social publish failed: ${e.message}")
            // not fatal for billing itself; continue
        }

        // 4) mark gift_tx as processed in DB
        markGiftProcessed(evt.gift_tx_id)

        // 5) ack Redis stream
        billingRedis.consumerCommands.xack(RedisStreams.BILLING_EVENTS, consumerGroup, msg.id).awaitFuture()
    }

    private suspend fun isGiftProcessed(giftTxId: Long): Boolean = withContext(Dispatchers.IO) {
        val conn = dataSource.connection
        try {
            val ps = conn.prepareStatement("SELECT status FROM gift_transactions WHERE id = ?")
            ps.setLong(1, giftTxId)
            val rs = ps.executeQuery()
            val processed = if (rs.next()) rs.getString("status") == "processed" else false
            rs.close()
            ps.close()
            processed
        } finally {
            try { conn.close() } catch (_: Exception) {}
        }
    }

    private suspend fun markGiftProcessed(giftTxId: Long) = withContext(Dispatchers.IO) {
        val conn = dataSource.connection
        try {
            val ps = conn.prepareStatement("UPDATE gift_transactions SET status = 'processed', processed_at = now() WHERE id = ?")
            ps.setLong(1, giftTxId)
            ps.executeUpdate()
            ps.close()
        } finally {
            try { conn.close() } catch (_: Exception) {}
        }
    }

    private fun buildRoomGiftPayload(evt: BillingOutboxPayloadLite): String {
        // Build the canonical payload that clients expect
        // For simplicity use a small JSON map; replace with your official LiveEvent.Gift serialization
        val map = mapOf(
            "type" to "gift",
            "gift_tx_id" to evt.gift_tx_id.toString(),
            "from_user_id" to evt.from_user_id.toString(),
            "to_user_id" to evt.to_user_id.toString(),
            "stream_id" to evt.stream_id,
            "gift_type" to evt.gift_type,
            "quantity" to evt.quantity.toString(),
            "coins_amount" to evt.coins_amount.toString(),
            "recipient_coins" to evt.recipient_coins.toString(),
            "created_at" to evt.created_at
        )
        return json.encodeToString(map)
    }

    suspend fun isHealthy(): Boolean = true
}