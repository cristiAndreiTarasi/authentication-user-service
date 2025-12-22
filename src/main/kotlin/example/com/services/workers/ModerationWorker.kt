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

/**
* Specialized worker for processing moderation events from Redis Streams
* Handles chat moderation, audit logs, and suspicious activity detection
*
* Separated from real-time event delivery which uses Pub/Sub
*/
class ModerationWorker(
    private val redisService: RedisService
) {
    private val consumerGroup = "moderation_group"
    private val consumerId = "moderation-consumer-${System.getenv("HOSTNAME") ?: "local"}"

    suspend fun start() {
        println("🛡️ ModerationWorker starting for stream: ${RedisStreams.MODERATION_EVENTS}")

        try {
            redisService.createConsumerGroupIfNotExists(RedisStreams.MODERATION_EVENTS, consumerGroup)
        } catch (e: Exception) {
            println("WARN: Error creating moderation consumer group: ${e.message}")
        }

        while (true) {
            try {
                val messages = redisService.consumerCommands.xreadgroup(
                    Consumer.from(consumerGroup, consumerId),
                    XReadArgs.Builder.block(5000).count(50),
                    XReadArgs.StreamOffset.from(RedisStreams.MODERATION_EVENTS, ">")
                ).awaitFuture()

                if (messages.isNullOrEmpty()) {
                    delay(100)
                    continue
                }

                println("🛡️ ModerationWorker processing ${messages.size} messages")

                for (msg in messages) {
                    try {
                        processModerationMessage(msg)
                    } catch (e: Exception) {
                        println("ERROR: Failed to process moderation message ${msg.id}: ${e.message}")
                        redisService.consumerCommands.xack(RedisStreams.MODERATION_EVENTS, consumerGroup, msg.id).awaitFuture()
                    }
                }
            } catch (e: Exception) {
                println("ERROR: ModerationWorker error: ${e.message}")
                delay(1000)
            }
        }
    }

    private suspend fun processModerationMessage(msg: StreamMessage<String, String>) {
        val raw = msg.body["event"] ?: return

        val event = AppJson.decodeFromString<LiveEvent>(raw)
        println("🛡️ Processing moderation event: ${event::class.simpleName} for room ${event.roomId}")

        // Here we can add additional moderation processing:
        // - Audit logging
        // - Compliance reporting
        // - Moderation analytics
        // - Alerting for suspicious patterns

        // Ack the message after processing
        redisService.consumerCommands.xack(RedisStreams.MODERATION_EVENTS, consumerGroup, msg.id).awaitFuture()
    }

    suspend fun isHealthy(): Boolean = true
}
