package example.com.services.workers

import example.com.config.AppJson
import example.com.config.awaitFuture
import example.com.routes.dtos.LiveEvent
import example.com.services.redis.RedisService
import example.com.services.redis.RedisStreams
import io.lettuce.core.Consumer
import io.lettuce.core.StreamMessage
import io.lettuce.core.XReadArgs
import kotlinx.coroutines.delay

class BillingWorker(
    private val redisService: RedisService
) {
    private val consumerGroup = "billing_group"
    private val consumerId = "billing-consumer-${System.getenv("HOSTNAME") ?: "local"}"

    suspend fun start() {
        println("💰 BillingWorker starting for stream: ${RedisStreams.BILLING_STREAM}")

        try {
            redisService.createConsumerGroupIfNotExists(RedisStreams.BILLING_STREAM, consumerGroup)
        } catch (e: Exception) {
            println("WARN: Error creating billing consumer group: ${e.message}")
        }

        while (true) {
            try {
                val messages = redisService.consumerCommands.xreadgroup(
                    Consumer.from(consumerGroup, consumerId),
                    XReadArgs.Builder.block(5000).count(50),
                    XReadArgs.StreamOffset.from(RedisStreams.BILLING_STREAM, ">")
                ).awaitFuture()

                if (messages.isNullOrEmpty()) {
                    delay(100)
                    continue
                }

                println("💰 BillingWorker processing ${messages.size} messages")

                for (msg in messages) {
                    try {
                        processBillingMessage(msg)
                    } catch (e: Exception) {
                        println("ERROR: Failed to process billing message ${msg.id}: ${e.message}")
                        redisService.consumerCommands.xack(RedisStreams.BILLING_STREAM, consumerGroup, msg.id).awaitFuture()
                    }
                }
            } catch (e: Exception) {
                println("ERROR: BillingWorker error: ${e.message}")
                delay(1000)
            }
        }
    }

    private suspend fun processBillingMessage(msg: StreamMessage<String, String>) {
        val raw = msg.body["event"] ?: return

        val event = AppJson.decodeFromString<LiveEvent.Gift>(raw)
        println("💰 Processing gift event: ${event.giftId} x${event.quantity} from user ${event.initiatorId}")

        // Here you can add additional billing processing:
        // - Payment processing
        // - Revenue tracking
        // - Payout calculations
        // - Financial reporting

        // Ack the message after processing
        redisService.consumerCommands.xack(RedisStreams.BILLING_STREAM, consumerGroup, msg.id).awaitFuture()
    }

    suspend fun isHealthy(): Boolean = true
}