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

class AnalyticsWorker(
    private val redisService: RedisService
) {
    private val consumerGroup = "analytics_group"
    private val consumerId = "analytics-consumer-${System.getenv("HOSTNAME") ?: "local"}"

    suspend fun start() {
        println("📊 AnalyticsWorker starting for stream: ${RedisStreams.ANALYTICS_STREAM}")

        try {
            redisService.createConsumerGroupIfNotExists(RedisStreams.ANALYTICS_STREAM, consumerGroup)
        } catch (e: Exception) {
            println("WARN: Error creating analytics consumer group: ${e.message}")
        }

        while (true) {
            try {
                val messages = redisService.consumerCommands.xreadgroup(
                    Consumer.from(consumerGroup, consumerId),
                    XReadArgs.Builder.block(5000).count(100),
                    XReadArgs.StreamOffset.from(RedisStreams.ANALYTICS_STREAM, ">")
                ).awaitFuture()

                if (messages.isNullOrEmpty()) {
                    delay(100)
                    continue
                }

                println("📊 AnalyticsWorker processing ${messages.size} messages")

                for (msg in messages) {
                    try {
                        processAnalyticsMessage(msg)
                    } catch (e: Exception) {
                        println("ERROR: Failed to process analytics message ${msg.id}: ${e.message}")
                        redisService.consumerCommands.xack(RedisStreams.ANALYTICS_STREAM, consumerGroup, msg.id).awaitFuture()
                    }
                }
            } catch (e: Exception) {
                println("ERROR: AnalyticsWorker error: ${e.message}")
                delay(1000)
            }
        }
    }

    private suspend fun processAnalyticsMessage(msg: StreamMessage<String, String>) {
        val raw = msg.body["event"] ?: return

        val event = AppJson.decodeFromString<LiveEvent>(raw)
        println("📊 Processing analytics event: ${event::class.simpleName} for room ${event.roomId}")

        // Here we can add additional analytics processing:
        // - Engagement metrics
        // - Viewer behavior analysis
        // - Real-time dashboards
        // - Business intelligence

        // Ack the message after processing
        redisService.consumerCommands.xack(RedisStreams.ANALYTICS_STREAM, consumerGroup, msg.id).awaitFuture()
    }

    suspend fun isHealthy(): Boolean = true
}