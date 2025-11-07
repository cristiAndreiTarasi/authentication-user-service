package example.com.services

import example.com.LiveEventJson
import example.com.routes.dtos.LiveEvent
import example.com.services.redis.RedisService
import io.lettuce.core.Consumer
import io.lettuce.core.RedisFuture
import io.lettuce.core.XReadArgs
import io.lettuce.core.StreamMessage
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.time.Duration
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
* Specialized worker for processing moderation events from Redis Streams
* Handles chat moderation, audit logs, and suspicious activity detection
*
* Separated from real-time event delivery which uses Pub/Sub
*/
class ModerationWorker(
    private val redisService: RedisService
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val consumerGroup = "moderation_group"
    private val consumerId = "moderation_worker-${System.getenv("HOSTNAME") ?: "local"}"

    private var isHealthy: Boolean = false

    /**
     * Use the same awaitFuture pattern that works in CrossInstanceBroadcaster
     */
    private suspend fun <T> awaitFuture(future: RedisFuture<T>): T? =
        suspendCoroutine { cont ->
            when {
                future.isDone -> {
                    try {
                        cont.resume(future.get())
                    } catch (e: Exception) {
                        cont.resumeWithException(e)
                    }
                }
                future.isCancelled -> cont.resumeWithException(CancellationException("RedisFuture cancelled"))
                else -> {
                    future.handle { res, err ->
                        if (err != null) cont.resumeWithException(err) else cont.resume(res)
                    }
                }
            }
        }

    /**
     * Processes the moderation stream for durable workflow processing
     * - Chat message moderation
     * - Audit logging
     * - Suspicious activity detection
     */
    suspend fun processModerationStream() {
        try {
            redisService.createConsumerGroupIfNotExists(RedisService.MODERATION_STREAM, consumerGroup)
        } catch (e: Exception) {
            println("DEBUG: Moderation group create error: ${e.message}")
        }

        isHealthy = true

        while (true) {
            try {
                val messages = awaitFuture(redisService.consumerCommands.xreadgroup(
                    Consumer.from(consumerGroup, consumerId),
                    XReadArgs.Builder.block(500).count(10),
                    XReadArgs.StreamOffset.from(RedisService.MODERATION_STREAM, ">")
                ))

                if (messages.isNullOrEmpty()) {
                    delay(100)
                    continue
                }

                // Process messages sequentially for ordered moderation
                for (msg in messages) {
                    try {
                        processModerationMessage(msg)
                        awaitFuture(redisService.consumerCommands.xack(
                            RedisService.MODERATION_STREAM,
                            consumerGroup,
                            msg.id
                        ))
                    } catch (e: Exception) {
                        println("DEBUG: Failed to process moderation message ${msg.id}: ${e.message}")
                        // Don't ack on error - allow retry
                    }
                }

            } catch (e: Exception) {
                println("DEBUG: Moderation worker error: ${e.message}")
                isHealthy = false
                delay(1000)
                isHealthy = true
            }
        }
    }

    private suspend fun processModerationMessage(msg: StreamMessage<String, String>) {
        val eventJson = msg.body["event"] ?: return

        try {
            val event = LiveEventJson.decodeFromString<LiveEvent>(eventJson)

            when (event) {
                is LiveEvent.ChatMessage -> {
                    // Auto-moderation logic
                    if (containsBadWords(event.text)) {
                        println("MODERATION: Flagged message from ${event.initiatorId}: ${event.text}")
                        // Could trigger automatic mute/kick based on severity
                    }

                    // Store in persistent moderation log
                    storeModerationLog(event)
                }

                is LiveEvent.KickUser, is LiveEvent.MuteUser -> {
                    // Audit log for moderation actions
                    storeAuditLog(event)
                }

                else -> {
                    // Handle other moderation-related events
                }
            }
        } catch (e: Exception) {
            println("DEBUG: Error parsing moderation event: ${e.message}")
        }
    }

    private fun containsBadWords(text: String): Boolean {
        // Simple bad word detection - replace with actual moderation service
        val badWords = listOf("badword1", "badword2", "spam")
        return badWords.any { text.contains(it, ignoreCase = true) }
    }

    private suspend fun storeModerationLog(event: LiveEvent) {
        // Store in database or external moderation service
        // This is where you'd integrate with actual moderation APIs
        println("MODERATION: Stored log for event: ${event::class.simpleName}")
    }

    private suspend fun storeAuditLog(event: LiveEvent) {
        // Store moderation actions in audit log
        println("AUDIT: Stored audit log for: ${event::class.simpleName}")
    }

    fun isHealthy(): Boolean = isHealthy
}