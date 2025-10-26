package example.com.services.notifications

import example.com.schemas.NotificationSchema
import example.com.services.redis.SocialEvent
import example.com.services.redis.RedisService
import io.lettuce.core.Consumer
import io.lettuce.core.RedisCommandExecutionException
import io.lettuce.core.StreamMessage
import io.lettuce.core.XGroupCreateArgs
import io.lettuce.core.XReadArgs
import io.lettuce.core.RedisException
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class NotificationWorker(
    private val redisService: RedisService,
    private val notificationSchema: NotificationSchema,
    private val consumerGroup: String = "notifications-group",
    private val consumerId: String = "notif-consumer-${System.getenv("HOSTNAME") ?: "local"}",
    private val streamKey: String = "streams:social:events" // keep consistent with RedisService producer
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun run() {
        // Create consumer group if not exists
        try {
            redisService.consumerCommands.xgroupCreate(
                XReadArgs.StreamOffset.from(streamKey, "0-0"),
                consumerGroup,
                XGroupCreateArgs.Builder.mkstream(true)
            )
        } catch (e: RedisCommandExecutionException) {
            if (!e.message.orEmpty().contains("BUSYGROUP")) throw e
        } catch (_: Throwable) {}

        while (true) {
            try {
                val messages: List<StreamMessage<String, String>> =
                    redisService.consumerCommands.xreadgroup(
                        Consumer.from(consumerGroup, consumerId),
                        XReadArgs.Builder.block(5_000).count(100),
                        XReadArgs.StreamOffset.from(streamKey, ">")
                    )

                if (messages.isEmpty()) {
                    delay(100)
                    continue
                }

                for (msg in messages) {
                    val raw = msg.body["event"]
                    if (raw == null) {
                        redisService.consumerCommands.xack(streamKey, consumerGroup, msg.id)
                        continue
                    }

                    try {
                        val event = json.decodeFromString(SocialEvent.serializer(), raw)

                        when (event.type.lowercase()) {
                            "follow" -> {
                                val actorIdInt = event.actorId.toIntOrNull()
                                val targetIdInt = event.targetId.toIntOrNull()
                                val actorName = event.actorUsername ?: "Someone"
                                val text = "$actorName followed you"

                                val payload = json.encodeToString(
                                    mapOf(
                                        "type" to "notification",
                                        "subtype" to "follow",
                                        "text" to text,
                                        "actorId" to event.actorId,
                                        "actorUsername" to event.actorUsername,
                                        "ts" to event.ts
                                    )
                                )

                                // Try real-time delivery
                                val delivered = NotificationSessionRegistry.sendToUser(targetIdInt ?: -1, payload)

                                // If not delivered, persist
                                if (!delivered && targetIdInt != null) {
                                    notificationSchema.insertNotification(
                                        userId = targetIdInt,
                                        actorId = actorIdInt,
                                        type = "follow",
                                        text = text,
                                        metaJson = """{"actorUsername":"${event.actorUsername ?: ""}"}"""
                                    )
                                }
                            }

                            "unfollow" -> {
                                // Optional: notify or ignore; implement similarly if needed
                            }

                            else -> {
                                // handle other social events similarly if desired
                            }
                        }

                        // ACK on success
                        redisService.consumerCommands.xack(streamKey, consumerGroup, msg.id)
                    } catch (e: Throwable) {
                        // Parsing / send error: do NOT ack so it can be retried
                        println("NotificationWorker: error handling message id=${msg.id}: ${e.message}")
                    }
                }
            } catch (e: RedisException) {
                // Redis connectivity issue -> backoff
                delay(1_000)
            } catch (e: Throwable) {
                // Unexpected -> avoid tight loop
                println("NotificationWorker unexpected error: ${e.message}")
                delay(1_000)
            }
        }
    }
}
