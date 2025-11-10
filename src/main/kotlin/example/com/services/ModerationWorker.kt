package example.com.services

import example.com.LiveEventJson
import example.com.routes.dtos.LiveEvent
import example.com.routes.dtos.withDefaults
import example.com.services.redis.RedisService
import example.com.services.ws_session.CrossInstanceBroadcaster
import example.com.services.ws_session.DistributedPermissionManager
import example.com.services.ws_session.DistributedSessionManager
import example.com.services.ws_session.SessionManager
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.lettuce.core.Consumer
import io.lettuce.core.RedisFuture
import io.lettuce.core.XReadArgs
import io.lettuce.core.StreamMessage
import kotlinx.coroutines.*
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json
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
    private val redisService: RedisService,
    private val distributedPermissionManager: DistributedPermissionManager,
    private val distributedSessionManager: DistributedSessionManager,
    private val crossInstanceBroadcaster: CrossInstanceBroadcaster
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val consumerGroup = "moderation_group"
    private val consumerId = "moderation_worker-${System.getenv("HOSTNAME") ?: "local"}"
    private val liveEventPolymorphic = PolymorphicSerializer(LiveEvent::class)

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
        println("MODERATIONWORKER: inside processModerationStream()")
        try {
            println("MODERATIONWORKER: inside processModerationStream() try")
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

                is LiveEvent.KickUser -> {
                    // Execute the kick action across all instances
                    executeKickUser(event.roomId, event.targetUserId)

                    // Store in audit log
                    storeAuditLog(event)
                }

                is LiveEvent.MuteUser -> {
                    // Execute the mute action
                    executeMuteUser(event.roomId, event.targetUserId)

                    // Store in audit log
                    storeAuditLog(event)
                }

                is LiveEvent.UnmuteUser -> {
                    // Execute the unmute action
                    executeUnmuteUser(event.roomId, event.targetUserId)

                    // Store in audit log
                    storeAuditLog(event)
                }

                is LiveEvent.GrantModerator -> {
                    // Execute the grant moderator action
                    executeGrantModerator(event.roomId, event.targetUserId)

                    // Store in audit log
                    storeAuditLog(event)
                }

                is LiveEvent.RevokeModerator -> {
                    // Execute the revoke moderator action
                    executeRevokeModerator(event.roomId, event.targetUserId)

                    // Store in audit log
                    storeAuditLog(event)
                }

                else -> {
                    // Handle other moderation-related events
                    println("MODERATION: Processing other event: ${event::class.simpleName}")
                    storeAuditLog(event)
                }
            }
        } catch (e: Exception) {
            println("DEBUG: Error parsing moderation event: ${e.message}")
        }
    }

    private suspend fun executeKickUser(roomId: String, targetUserId: String) {
        try {
            // Update permission manager to mark user as kicked
            distributedPermissionManager.kickUser(roomId, targetUserId)

            // Get username from distributed session manager
            val sessionInfo = distributedSessionManager.getSessionInfo(targetUserId, roomId)
            val username = sessionInfo?.get("username") ?: "User"

            // Find and close the user's WebSocket session across all instances
            // Note: We can only close local sessions, remote sessions will be handled via cross-instance messaging
            // You'll need to implement SessionManager.getSession or equivalent
             SessionManager.getSession(roomId, targetUserId)?.let { session ->
                 try {
                     val kickUserEvent = LiveEvent.KickUser(
                         roomId = roomId,
                         initiatorId = "system",
                         targetUserId = targetUserId,
                         timestamp = System.currentTimeMillis()
                     )

                     // Send kick event to the user before closing connection
                     val json = LiveEventJson.encodeToString(liveEventPolymorphic, kickUserEvent.withDefaults())
                     session.send(Frame.Text(json))
                     session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "kicked"))
                 } catch (e: Exception) {
                     // Connection already closed
                 } finally {
                     // Remove from both local and distributed session managers
                     SessionManager.removeSession(session)
                     distributedSessionManager.removeSession(targetUserId, roomId)
                 }
             }

            // Broadcast system message about the kick using cross-instance broadcaster
            val systemMessage = LiveEvent.SystemMessage(
                roomId = roomId,
                text = "$username was kicked from the stream",
                timestamp = System.currentTimeMillis()
            )
            crossInstanceBroadcaster.broadcastToRoom(roomId, systemMessage)

            println("MODERATION: Successfully kicked user $targetUserId from room $roomId")
        } catch (e: Exception) {
            println("MODERATION: Error kicking user $targetUserId from room $roomId: ${e.message}")
        }
    }

    private suspend fun executeMuteUser(roomId: String, targetUserId: String) {
        try {
            distributedPermissionManager.muteUser(roomId, targetUserId)

            // Get username for notification
            val sessionInfo = distributedSessionManager.getSessionInfo(targetUserId, roomId)
            val username = sessionInfo?.get("username") ?: "User"

            // Broadcast system message
            val systemMessage = LiveEvent.SystemMessage(
                roomId = roomId,
                text = "$username was muted",
                timestamp = System.currentTimeMillis()
            )
            crossInstanceBroadcaster.broadcastToRoom(roomId, systemMessage)

            println("MODERATION: Successfully muted user $targetUserId in room $roomId")
        } catch (e: Exception) {
            println("MODERATION: Error muting user $targetUserId in room $roomId: ${e.message}")
        }
    }

    private suspend fun executeUnmuteUser(roomId: String, targetUserId: String) {
        try {
            distributedPermissionManager.unmuteUser(roomId, targetUserId)

            // Get username for notification
            val sessionInfo = distributedSessionManager.getSessionInfo(targetUserId, roomId)
            val username = sessionInfo?.get("username") ?: "User"

            // Broadcast system message
            val systemMessage = LiveEvent.SystemMessage(
                roomId = roomId,
                text = "$username was unmuted",
                timestamp = System.currentTimeMillis()
            )
            crossInstanceBroadcaster.broadcastToRoom(roomId, systemMessage)

            println("MODERATION: Successfully unmuted user $targetUserId in room $roomId")
        } catch (e: Exception) {
            println("MODERATION: Error unmuting user $targetUserId in room $roomId: ${e.message}")
        }
    }

    private suspend fun executeGrantModerator(roomId: String, targetUserId: String) {
        try {
            distributedPermissionManager.grantModerator(roomId, targetUserId)

            // Get username for notification
            val sessionInfo = distributedSessionManager.getSessionInfo(targetUserId, roomId)
            val username = sessionInfo?.get("username") ?: "User"

            // Broadcast system message
            val systemMessage = LiveEvent.SystemMessage(
                roomId = roomId,
                text = "$username was granted moderator privileges",
                timestamp = System.currentTimeMillis()
            )
            crossInstanceBroadcaster.broadcastToRoom(roomId, systemMessage)

            println("MODERATION: Successfully granted moderator to user $targetUserId in room $roomId")
        } catch (e: Exception) {
            println("MODERATION: Error granting moderator to user $targetUserId in room $roomId: ${e.message}")
        }
    }

    private suspend fun executeRevokeModerator(roomId: String, targetUserId: String) {
        try {
            distributedPermissionManager.revokeModerator(roomId, targetUserId)

            // Get username for notification
            val sessionInfo = distributedSessionManager.getSessionInfo(targetUserId, roomId)
            val username = sessionInfo?.get("username") ?: "User"

            // Broadcast system message
            val systemMessage = LiveEvent.SystemMessage(
                roomId = roomId,
                text = "$username was removed as moderator",
                timestamp = System.currentTimeMillis()
            )
            crossInstanceBroadcaster.broadcastToRoom(roomId, systemMessage)

            println("MODERATION: Successfully revoked moderator from user $targetUserId in room $roomId")
        } catch (e: Exception) {
            println("MODERATION: Error revoking moderator from user $targetUserId in room $roomId: ${e.message}")
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