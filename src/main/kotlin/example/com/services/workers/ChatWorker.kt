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

// ChatWorker.kt
class ChatWorker(
    private val redisService: RedisService
) {
    private val consumerGroup = "chat_group"
    private val consumerId = "chat-consumer-${System.getenv("HOSTNAME") ?: "local"}"

    suspend fun start() {
        println("💬 ChatWorker starting for stream: ${RedisStreams.CHAT_STREAM}")

        try {
            redisService.createConsumerGroupIfNotExists(RedisStreams.CHAT_STREAM, consumerGroup)
        } catch (e: Exception) {
            println("WARN: Error creating chat consumer group: ${e.message}")
        }

        while (true) {
            try {
                val messages = redisService.consumerCommands.xreadgroup(
                    Consumer.from(consumerGroup, consumerId),
                    XReadArgs.Builder.block(5000).count(50),
                    XReadArgs.StreamOffset.from(RedisStreams.CHAT_STREAM, ">")
                ).awaitFuture()

                if (messages.isNullOrEmpty()) {
                    delay(100)
                    continue
                }

                println("💬 ChatWorker processing ${messages.size} messages")

                for (msg in messages) {
                    try {
                        processChatMessage(msg)
                    } catch (e: Exception) {
                        println("ERROR: Failed to process chat message ${msg.id}: ${e.message}")
                        // Ack problematic messages to avoid blocking
                        redisService.consumerCommands.xack(RedisStreams.CHAT_STREAM, consumerGroup, msg.id).awaitFuture()
                    }
                }
            } catch (e: Exception) {
                println("ERROR: ChatWorker error: ${e.message}")
                delay(1000)
            }
        }
    }

    private suspend fun processChatMessage(msg: StreamMessage<String, String>) {
        val raw = msg.body["event"] ?: return

        val event = AppJson.decodeFromString<LiveEvent>(raw)
        println("💬 Processing chat event: ${event::class.simpleName} for room ${event.roomId}")

        // Here we can add additional chat processing logic:
        // - Sentiment analysis
        // - Spam detection
        // - Language filtering
        // - Chat analytics

        // Ack the message after processing
        redisService.consumerCommands.xack(RedisStreams.CHAT_STREAM, consumerGroup, msg.id).awaitFuture()
    }

    suspend fun isHealthy(): Boolean = true
}
