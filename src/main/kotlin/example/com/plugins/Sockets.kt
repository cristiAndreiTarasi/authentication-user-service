package example.com.plugins

import example.com.LiveEventJson
import example.com.ModerationMessages
import example.com.ModerationReason
import example.com.ModerationSeverity
import example.com.NotificationEventJson
import example.com.ProfileUpdateType
import example.com.services.ServiceManager
import example.com.routes.dtos.LiveEvent
import example.com.routes.dtos.NotificationEvent
import example.com.routes.dtos.withDefaults
import example.com.schemas.NotificationSchema
import example.com.schemas.UserSchema
import example.com.services.notifications.NotificationSessionRegistry
import example.com.services.redis.RedisService
import example.com.services.token.TokenService
import example.com.services.ws_session.LiveRoomSessionRegistry
import example.com.services.ws_session.WebSocketAuthHelper
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.serialization.PolymorphicSerializer
import java.time.Duration

/**
 * Configures WebSocket endpoints for live streaming rooms and notifications with distributed session management.
 *
 * @param redisService Redis service for event streaming and persistence
 * @param userSchema Database access for user data
 * @param notificationSchema Database access for notification data
 * @param authTokenService Service for JWT authentication
 */
fun Application.configureSockets(
    redisService: RedisService,
    userSchema: UserSchema,
    notificationSchema: NotificationSchema,
    authTokenService: TokenService,
    serviceManager: ServiceManager
) {
    install(WebSockets) {
        pingPeriod   = Duration.ofSeconds(15)
        timeout      = Duration.ofSeconds(15)
        maxFrameSize = Long.MAX_VALUE
        masking      = false
    }

    // Extract services from container for cleaner code
    val distributedSessionManager = serviceManager.distributedSessionManager
    val distributedPermissionManager = serviceManager.distributedPermissionManager
    val crossInstanceBroadcaster = serviceManager.crossInstanceBroadcaster

    // Create polymorphic serializers for event types
    val liveEventPolymorphic = PolymorphicSerializer(LiveEvent::class)
    val notificationEventPolymorphic = PolymorphicSerializer(NotificationEvent::class)

    /**
    * Updates and broadcasts stream statistics (viewer count, total likes) for a room.
    *
    * @param roomId The room ID to update stats for
    * @param redisManager Redis service for storing and broadcasting stats
    */
    suspend fun updateStreamStats(roomId: String, redisManager: RedisService) {
        val viewerCount = distributedSessionManager.getRoomUsers(roomId).size
        val totalLikes = redisManager.getCounter("room:$roomId:likes") ?: 0

        val event = LiveEvent.StreamStats(
            roomId = roomId,
            viewerCount = viewerCount,
            totalLikes = totalLikes,
            timestamp = System.currentTimeMillis()
        )

        crossInstanceBroadcaster.broadcastToRoom(roomId, event)
    }

    /**
     * Executes moderation actions immediately and broadcasts via Pub/Sub
     */
    suspend fun executeModerationAction(event: LiveEvent) {
        when (event) {
            is LiveEvent.KickUser -> {
                // persist kick permission (kicked set) and log inside manager
                distributedPermissionManager.kickUser(event.roomId, event.targetUserId) // ADDED/ENSURED

                val sessionInfo = distributedSessionManager.getSessionInfo(event.targetUserId, event.roomId)
                val username = sessionInfo?.get("username") ?: "User"

                // Close local session if exists
                LiveRoomSessionRegistry.getSession(event.roomId, event.targetUserId)?.let { session ->
                    try {
                        val json = LiveEventJson.encodeToString(liveEventPolymorphic, event.withDefaults())
                        session.send(Frame.Text(json))
                        session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "kicked"))
                    } catch (e: Exception) {
                        println("MODERATION: Error closing WebSocket: ${e.message}")
                    } finally {
                        LiveRoomSessionRegistry.removeSession(session)
                        distributedSessionManager.removeSession(event.targetUserId, event.roomId)
                        updateStreamStats(event.roomId, redisService)
                    }
                }

                // Broadcast typed KickUser event so client updates state
                try {
                    crossInstanceBroadcaster.broadcastToRoom(event.roomId, event) // ADDED
                } catch (e: Exception) {
                    println("WARN: failed to broadcast KickUser event: ${e.message}") // ADDED
                }

                // Broadcast system message
                val systemMessage = LiveEvent.SystemMessage(
                    roomId = event.roomId,
                    text = "$username was kicked from the stream",
                    timestamp = System.currentTimeMillis()
                )
                crossInstanceBroadcaster.broadcastToRoom(event.roomId, systemMessage)

                // Persist to moderation stream
                try {
                    redisService.addToModerationStream(event) // ADDED
                } catch (e: Exception) {
                    println("WARN: Failed to persist KickUser to moderation stream: ${e.message}") // ADDED
                }
            }

            is LiveEvent.MuteUser -> {
                distributedPermissionManager.muteUser(event.roomId, event.targetUserId)

                // Broadcast typed MuteUser event so clients update state
                try {
                    crossInstanceBroadcaster.broadcastToRoom(event.roomId, event) // ADDED
                } catch (e: Exception) {
                    println("WARN: failed to broadcast MuteUser event: ${e.message}") // ADDED
                }

                val sessionInfo = distributedSessionManager.getSessionInfo(event.targetUserId, event.roomId)
                val username = sessionInfo?.get("username") ?: "User"

                val systemMessage = LiveEvent.SystemMessage(
                    roomId = event.roomId,
                    text = "$username was muted",
                    timestamp = System.currentTimeMillis()
                )
                crossInstanceBroadcaster.broadcastToRoom(event.roomId, systemMessage)

                // Persist to moderation stream
                try {
                    redisService.addToModerationStream(event) // ADDED
                } catch (e: Exception) {
                    println("WARN: Failed to persist MuteUser to moderation stream: ${e.message}") // ADDED
                }
            }

            is LiveEvent.UnmuteUser -> {
                distributedPermissionManager.unmuteUser(event.roomId, event.targetUserId)

                // Broadcast typed UnmuteUser event so clients update state
                try {
                    crossInstanceBroadcaster.broadcastToRoom(event.roomId, event) // ADDED
                } catch (e: Exception) {
                    println("WARN: failed to broadcast UnmuteUser event: ${e.message}") // ADDED
                }

                val sessionInfo = distributedSessionManager.getSessionInfo(event.targetUserId, event.roomId)
                val username = sessionInfo?.get("username") ?: "User"

                val systemMessage = LiveEvent.SystemMessage(
                    roomId = event.roomId,
                    text = "$username was unmuted",
                    timestamp = System.currentTimeMillis()
                )
                crossInstanceBroadcaster.broadcastToRoom(event.roomId, systemMessage)

                // Persist to moderation stream
                try {
                    redisService.addToModerationStream(event) // ADDED
                } catch (e: Exception) {
                    println("WARN: Failed to persist UnmuteUser to moderation stream: ${e.message}") // ADDED
                }
            }

            is LiveEvent.GrantModerator -> {
                // Persist permission (CHANGED: do this server-side)
                distributedPermissionManager.grantModerator(event.roomId, event.targetUserId)

                // Verify persistence (log)
                val nowModerator = distributedPermissionManager.isModerator(event.roomId, event.targetUserId)

                // Broadcast typed event (so clients update their UI)
                try {
                    crossInstanceBroadcaster.broadcastToRoom(event.roomId, event)
                } catch (e: Exception) {
                    println("WARN: failed to broadcast GrantModerator event: ${e.message}")
                }

                // Keep the friendly system message in chat
                val sessionInfo = distributedSessionManager.getSessionInfo(event.targetUserId, event.roomId)
                val username = sessionInfo?.get("username") ?: "User"
                val systemMessage = LiveEvent.SystemMessage(
                    roomId = event.roomId,
                    text = "$username was granted moderator privileges",
                    timestamp = System.currentTimeMillis()
                )
                crossInstanceBroadcaster.broadcastToRoom(event.roomId, systemMessage)

                // Persist action to moderation stream for audit
                try {
                    redisService.addToModerationStream(event) // ADDED
                } catch (e: Exception) {
                    println("WARN: Failed to persist GrantModerator to moderation stream: ${e.message}") // ADDED
                }
            }

            is LiveEvent.RevokeModerator -> {
                distributedPermissionManager.revokeModerator(event.roomId, event.targetUserId)

                // Broadcast typed RevokeModerator event
                try {
                    crossInstanceBroadcaster.broadcastToRoom(event.roomId, event) // ADDED
                } catch (e: Exception) {
                    println("WARN: failed to broadcast RevokeModerator event: ${e.message}") // ADDED
                }

                val sessionInfo = distributedSessionManager.getSessionInfo(event.targetUserId, event.roomId)
                val username = sessionInfo?.get("username") ?: "User"

                val systemMessage = LiveEvent.SystemMessage(
                    roomId = event.roomId,
                    text = "$username was removed as moderator",
                    timestamp = System.currentTimeMillis()
                )
                crossInstanceBroadcaster.broadcastToRoom(event.roomId, systemMessage)

                // Persist action to moderation stream for audit
                try {
                    redisService.addToModerationStream(event) // ADDED
                } catch (e: Exception) {
                    println("WARN: Failed to persist RevokeModerator to moderation stream: ${e.message}") // ADDED
                }
            }

            else -> {
                // Other moderation events can be handled here
                println("MODERATION: Unhandled moderation event type: ${event::class.simpleName}")
            }
        }
    }

    /**
    * Sends stream owner information to a specific WebSocket session.
    * Used when a new viewer joins to show who is streaming.
    */
    suspend fun sendStreamOwnerInfo(
        roomId: String,
        session: WebSocketSession,
        userSchema: UserSchema
    ) {
        val ownerId = distributedPermissionManager.getStreamOwner(roomId) ?: return

        // Get owner info from distributed permission manager or database
        val owner = userSchema.findById(ownerId.toInt()) ?: return

        val publisherInfoEvent = LiveEvent.PublisherInfoEvent(
            roomId = roomId,
            userId = ownerId,
            username = owner.username,
            avatarUrl = owner.imageUrl
        )

        val json = LiveEventJson.encodeToString(liveEventPolymorphic, publisherInfoEvent.withDefaults())
        session.send(Frame.Text(json))
    }

    /**
     * Broadcasts stream ended event to all connected clients.
     */
    suspend fun sendStreamEndedEvent(roomId: String) {
        val event = LiveEvent.StreamEndedEvent(roomId = roomId)
        crossInstanceBroadcaster.broadcastToRoom(roomId, event)

        val localSessions = LiveRoomSessionRegistry.getRoomSessions(roomId).toList()
        if (localSessions.isNotEmpty()) {
            localSessions.forEach { session ->
                try {
                    val closeReason = CloseReason(CloseReason.Codes.GOING_AWAY, "Stream ended by publisher")
                    session.close(closeReason)
                } catch (e: Exception) {
                    println("DEBUG: Error closing session: ${e.message}")
                } finally {
                    val sessionUserId = LiveRoomSessionRegistry.getUserId(session)
                    LiveRoomSessionRegistry.removeSession(session)
                    if (sessionUserId != null) {
                        distributedSessionManager.removeSession(sessionUserId, roomId)
                    }
                }
            }
        }
    }

    /**
     * Sends initial room state (viewer count, likes) to a newly connected client.
     */
    suspend fun sendInitialState(
        roomId: String,
        session: WebSocketSession,
        redisManager: RedisService
    ) {
        val viewerCount = distributedSessionManager.getRoomUsers(roomId).size
        val totalLikes = redisManager.getCounter("room:$roomId:likes") ?: 0

        val streamStatsEvent = LiveEvent.StreamStats(
            roomId = roomId,
            viewerCount = viewerCount,
            totalLikes = totalLikes
        )

        val json = LiveEventJson.encodeToString(liveEventPolymorphic, streamStatsEvent.withDefaults())
        session.send(Frame.Text(json))
    }

    /**
     * Sends acknowledgment back to the moderator that their action was queued
     */
    suspend fun sendModerationAck(
        session: WebSocketSession,
        roomId: String,
        actionType: String,
        targetUserId: String,
        success: Boolean = true
    ) {
        val ackEvent = LiveEvent.ModerationAck(
            roomId = roomId,
            actionType = actionType,
            targetUserId = targetUserId,
            success = success,
            timestamp = System.currentTimeMillis()
        )
        val json = LiveEventJson.encodeToString(liveEventPolymorphic, ackEvent.withDefaults())
        session.send(Frame.Text(json))
    }

    /**
    * Main handler for live room events. Processes different types of events
    * with clear separation between real-time (Pub/Sub) and durable (Streams) processing.
    *
    * Updated to use Pub/Sub for real-time delivery and Streams for durable workflows
    */
    suspend fun handleLiveRoomEvent(
        event: LiveEvent,
        userId: String,
        roomId: String,
        redisManager: RedisService,
        session: WebSocketSession? = null
    ) {
        // Ignore any client events if the user is kicked.
        if (distributedPermissionManager.isKicked(roomId, userId) && event.initiatorId != "system") {
            return
        }

        when (event) {
            // REAL-TIME EVENTS (Pub/Sub only)
            is LiveEvent.Like -> {
                redisManager.incrementCounter("room:$roomId:likes", event.count.toLong())
                val userTotal = redisManager.incrementUserLikeCount(roomId, userId, event.count.toLong())

                if (userTotal == 1L || userTotal % 100 == 0L) {
                    val sessionInfo = LiveRoomSessionRegistry.getSessionInfo(roomId, userId)
                    val username = sessionInfo?.username ?: "A viewer"

                    val message = when (userTotal) {
                        1L -> "$username sent their first like!"
                        else -> "$username has sent $userTotal likes!"
                    }

                    val systemEvent = LiveEvent.SystemMessage(
                        roomId = roomId,
                        text = message,
                        timestamp = System.currentTimeMillis()
                    )
                    crossInstanceBroadcaster.broadcastToRoom(roomId, systemEvent)
                }

                crossInstanceBroadcaster.broadcastToRoom(roomId, event)

                if (event.count > 10 || userTotal % 100 == 0L) {
                    redisManager.addToAnalyticsStream(event)
                }
            }

            is LiveEvent.JoinRoom -> {
                val systemMessage = LiveEvent.SystemMessage(
                    roomId = roomId,
                    text = "${event.username} joined the stream",
                    timestamp = System.currentTimeMillis()
                )

                crossInstanceBroadcaster.broadcastToRoom(roomId, event)
                crossInstanceBroadcaster.broadcastToRoom(roomId, systemMessage)
                updateStreamStats(roomId, redisManager)
            }

            is LiveEvent.LeaveRoom -> {
                val systemMessage = LiveEvent.SystemMessage(
                    roomId = roomId,
                    text = "${event.username} left the stream",
                    timestamp = System.currentTimeMillis()
                )

                crossInstanceBroadcaster.broadcastToRoom(roomId, event)
                crossInstanceBroadcaster.broadcastToRoom(roomId, systemMessage)
                updateStreamStats(roomId, redisManager)
            }

            // HYBRID EVENTS (Real-time + Durable)
            is LiveEvent.ChatMessage -> {
                redisManager.addToModerationStream(event)
                redisManager.addToAnalyticsStream(event)

                if (!distributedPermissionManager.isMuted(roomId, userId)) {
                    crossInstanceBroadcaster.broadcastToRoom(roomId, event)
                }

                redisManager.addToHistory(roomId, event)
            }

            is LiveEvent.Gift -> {
                crossInstanceBroadcaster.broadcastToRoom(roomId, event)
                redisManager.addToBillingStream(event)
                redisManager.addToAnalyticsStream(event)
                redisManager.incrementCounter("room:$roomId:gifts:${event.giftId}", event.quantity.toLong())
            }

            // MODERATION EVENTS (Real-time via Pub/Sub + Durable via Streams)
            is LiveEvent.KickUser -> {
                if (distributedPermissionManager.isStreamOwner(roomId, userId) ||
                    distributedPermissionManager.isModerator(roomId, userId)) {
                    // Execute immediately and broadcast via Pub/Sub
                    executeModerationAction(event)

                    // Also persist to stream for audit (CHANGED: ensure durable storage)
                    try {
                        redisManager.addToModerationStream(event) // ADDED
                    } catch (e: Exception) {
                        println("WARN: Failed to persist KickUser to moderation stream: ${e.message}") // ADDED
                    }

                    // Send acknowledgment to moderator
                    session?.let {
                        sendModerationAck(it, roomId, "kick", event.targetUserId, true)
                    }
                } else {
                    session?.let {
                        sendModerationAck(it, roomId, "kick", event.targetUserId, false)
                    }
                }
            }

            is LiveEvent.MuteUser -> {
                if (distributedPermissionManager.isStreamOwner(roomId, userId) ||
                    distributedPermissionManager.isModerator(roomId, userId)) {

                    executeModerationAction(event)

                    // Persist to moderation stream for audit
                    try {
                        redisManager.addToModerationStream(event) // ADDED
                    } catch (e: Exception) {
                        println("WARN: Failed to persist MuteUser to moderation stream: ${e.message}") // ADDED
                    }

                    session?.let {
                        sendModerationAck(it, roomId, "mute", event.targetUserId, true)
                    }
                } else {
                    session?.let {
                        sendModerationAck(it, roomId, "mute", event.targetUserId, false)
                    }
                }
            }

            is LiveEvent.UnmuteUser -> {
                if (distributedPermissionManager.isStreamOwner(roomId, userId) ||
                    distributedPermissionManager.isModerator(roomId, userId)) {

                    executeModerationAction(event)

                    // Persist to moderation stream for audit
                    try {
                        redisManager.addToModerationStream(event) // ADDED
                    } catch (e: Exception) {
                        println("WARN: Failed to persist UnmuteUser to moderation stream: ${e.message}") // ADDED
                    }

                    session?.let {
                        sendModerationAck(it, roomId, "unmute", event.targetUserId, true)
                    }
                } else {
                    session?.let {
                        sendModerationAck(it, roomId, "unmute", event.targetUserId, false)
                    }
                }
            }

            is LiveEvent.GrantModerator -> {
                val streamOwner = distributedPermissionManager.getStreamOwner(roomId)

                // only owner can grant moderator
                if (distributedPermissionManager.isStreamOwner(roomId, userId)) {
                    try {
                        executeModerationAction(event)

                        // Also persist to stream for audit
                        try {
                            redisManager.addToModerationStream(event) // ADDED
                        } catch (e: Exception) {
                            println("WARN: Failed to persist GrantModerator to moderation stream: ${e.message}") // ADDED
                        }

                        session?.let {
                            sendModerationAck(it, roomId, "grant_moderator", event.targetUserId, true)
                        }
                    } catch (e: Exception) {
                        println("DEBUG: ERROR in GrantModerator handler: ${e.message}")
                        e.printStackTrace()
                    }
                } else {
                    session?.let {
                        sendModerationAck(it, roomId, "grant_moderator", event.targetUserId, false)
                    }
                }
            }

            is LiveEvent.RevokeModerator -> {
                if (distributedPermissionManager.isStreamOwner(roomId, userId)) {
                    executeModerationAction(event)

                    // Persist to moderation stream for audit
                    try {
                        redisManager.addToModerationStream(event) // ADDED
                    } catch (e: Exception) {
                        println("WARN: Failed to persist RevokeModerator to moderation stream: ${e.message}") // ADDED
                    }

                    session?.let {
                        sendModerationAck(it, roomId, "revoke_moderator", event.targetUserId, true)
                    }
                } else {
                    session?.let {
                        sendModerationAck(it, roomId, "revoke_moderator", event.targetUserId, false)
                    }
                }
            }

            // SYSTEM EVENTS (Mostly Real-time)
            is LiveEvent.SystemMessage -> {
                crossInstanceBroadcaster.broadcastToRoom(roomId, event)
                if (event.text.contains("violation", ignoreCase = true) ||
                    event.text.contains("terminated", ignoreCase = true)) {
                    redisManager.addToModerationStream(event)
                }
            }

            // COMPUTED STATE (Real-time only)
            is LiveEvent.StreamStats, is LiveEvent.PublisherInfoEvent -> {
                crossInstanceBroadcaster.broadcastToRoom(roomId, event)
            }

            // MODERATION WARNINGS (Real-time + Durable)
            is LiveEvent.ModerationWarningEvent -> {
                val streamOwnerId = distributedPermissionManager.getStreamOwner(roomId)
                val sessions = LiveRoomSessionRegistry.getRoomSessions(roomId)

                sessions.forEach { session ->
                    try {
                        val sessionUserId = LiveRoomSessionRegistry.getUserId(session)
                        val isStreamer = sessionUserId == streamOwnerId

                        val reason = try {
                            ModerationReason.valueOf(event.reason.uppercase())
                        } catch (e: Exception) { ModerationReason.OTHER }
                        val severity = try {
                            ModerationSeverity.valueOf(event.severity.uppercase())
                        } catch (e: Exception) { ModerationSeverity.WARNING }

                        val finalMessage = if (isStreamer) {
                            ModerationMessages.getStreamerWarningMessage(severity, reason)
                        } else {
                            ModerationMessages.getWarningMessage(severity, reason)
                        }

                        val userSpecificEvent = event.copy(message = finalMessage)
                        val json = LiveEventJson.encodeToString(liveEventPolymorphic, userSpecificEvent.withDefaults())
                        session.send(Frame.Text(json))
                    } catch (e: Exception) {
                        // ignore send failures
                    }
                }

                redisManager.addToModerationStream(event)
            }

            is LiveEvent.StreamTerminatedEvent -> {
                crossInstanceBroadcaster.broadcastToRoom(roomId, event)

                val streamOwnerId = distributedPermissionManager.getStreamOwner(roomId)

                val sessions = LiveRoomSessionRegistry.getRoomSessions(roomId)
                sessions.forEach { session ->
                    try {
                        val sessionUserId = LiveRoomSessionRegistry.getUserId(session)
                        val isStreamer = sessionUserId == streamOwnerId

                        val reason = try {
                            ModerationReason.valueOf(event.reason.uppercase())
                        } catch (e: Exception) {
                            ModerationReason.OTHER
                        }

                        val finalMessage = if (isStreamer) {
                            ModerationMessages.getStreamerTerminationMessage(reason)
                        } else {
                            ModerationMessages.getTerminationMessage(reason)
                        }

                        val userSpecificEvent = event.copy(message = finalMessage)
                        val json = LiveEventJson.encodeToString(liveEventPolymorphic, userSpecificEvent.withDefaults())
                        session.send(Frame.Text(json))
                    } catch (e: Exception) {
                        // ignore send failures
                    }
                }

                sessions.forEach { session ->
                    try {
                        session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Stream terminated"))
                    } catch (e: Exception) {
                        // ignore
                    } finally {
                        LiveRoomSessionRegistry.removeSession(session)
                        distributedSessionManager.removeSession(LiveRoomSessionRegistry.getUserId(session) ?: "", roomId)
                    }
                }

                distributedPermissionManager.removeRoom(roomId)
                redisManager.deleteCounters(roomId)
                redisManager.addToModerationStream(event)
            }

            is LiveEvent.ModerationClearEvent -> {
                crossInstanceBroadcaster.broadcastToRoom(roomId, event)

                val sessions = LiveRoomSessionRegistry.getRoomSessions(roomId)
                sessions.forEach { session ->
                    try {
                        val json = LiveEventJson.encodeToString(liveEventPolymorphic, event.withDefaults())
                        session.send(Frame.Text(json))
                    } catch (e: Exception) {
                        // ignore send failure
                    }
                }

                redisManager.addToModerationStream(event)
            }

            is LiveEvent.StreamEndedEvent -> {
                LiveRoomSessionRegistry.getSession(roomId, userId)?.let { session ->
                    try {
                        val closeReason = CloseReason(CloseReason.Codes.GOING_AWAY, "Stream ended")
                        session.close(closeReason)
                    } catch (e: Exception) {
                        println("DEBUG: Error closing session on StreamEndedEvent: ${e.message}")
                    } finally {
                        LiveRoomSessionRegistry.removeSession(session)
                        distributedSessionManager.removeSession(userId, roomId)
                    }
                }

                updateStreamStats(roomId, redisManager)
            }

            is LiveEvent.ModerationAck -> {
                println("WARNING: Received ModerationAck from client - this should only be sent by server")
                // Do nothing - this is a server-generated event
            }
        }
    }

    /**
    * Handles notification events from client WebSocket connections.
    * Processes mark-read events and sends updated counts to users.
    */
    suspend fun handleNotificationEvent(
        event: NotificationEvent,
        userId: Int,
        notificationSchema: NotificationSchema
    ) {
        when (event) {
            is NotificationEvent.MarkRead -> {
                // Mark notification as read in database
                val success = notificationSchema.markAsRead(userId, event.notificationId)
                if (success) {
                    println("DEBUG: User $userId marked notification ${event.notificationId} as read")

                    // Send updated unread count
                    val unreadCount = notificationSchema.getUnreadCount(userId)
                    val updateEvent = NotificationEvent.ProfileUpdate(
                        userId = userId.toString(),
                        updateType = ProfileUpdateType.UNREAD_COUNT,
                        count = unreadCount
                    ).withDefaults()

                    crossInstanceBroadcaster.sendToUser(userId, updateEvent)
                }
            }

            // Add other notification event types as needed
            else -> {
                println("DEBUG: Unhandled notification event type: ${event::class.simpleName}")
            }
        }
    }

    routing {
        route("/ws") {
            authenticate("auth-jwt") {
                /**
                 * WebSocket endpoint for live streaming rooms.
                 * Handles real-time events like chat, likes, moderation, etc.
                 *
                 * Path: /ws/liveRoom/{roomId}/{userId}
                 */
                webSocket("/liveRoom/{roomId}") {
                    val userId = WebSocketAuthHelper.authenticateUser(
                        call,
                        this,
                        authTokenService
                    ) ?: return@webSocket

                    val roomId = call.parameters["roomId"]!!
                    val user = userSchema.findById(userId.toInt())
                    val username = user?.username ?: "Unknown"

                    println("DEBUG: === NEW WEBSOCKET CONNECTION ===")
                    println("DEBUG: User $userId connected to room $roomId")

                    // Check if session exists in distributed manager and handle reconnection
                    if (distributedSessionManager.isUserInRoom(userId, roomId)) {
                        // User already has a session somewhere - close existing local session if any
                        LiveRoomSessionRegistry.getSession(roomId, userId)?.let { existingSession ->
                            try {
                                existingSession.close(CloseReason(CloseReason.Codes.NORMAL, "Reconnecting"))
                            } catch (e: Exception) {
                                // Ignore
                            } finally {
                                LiveRoomSessionRegistry.removeSession(existingSession)
                            }
                        }
                        // Note: We don't remove from distributed manager here to avoid race conditions
                    }

                    // Check if user is kicked
                    if (distributedPermissionManager.isKicked(roomId, userId)) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "kicked"))
                        return@webSocket
                    }

                    // Register session in both local and distributed managers
                    LiveRoomSessionRegistry.addSession(roomId, userId, username, this)
                    distributedSessionManager.addSession(roomId, userId, username)

                    try {
                        // Send chat history first
                        val history = redisService.getRoomHistory(roomId)
                        history.forEach { event ->
                            val json =
                                LiveEventJson.encodeToString(liveEventPolymorphic, event.withDefaults())
                            send(Frame.Text(json))
                        }

                        // Send initial state
                        sendInitialState(roomId, this, redisService)

                        // Send stream owner info if available
                        sendStreamOwnerInfo(roomId, this, userSchema)

                        // Handle join event
                        val joinEvent = LiveEvent.JoinRoom(
                            roomId = roomId,
                            initiatorId = userId,
                            username = username,
                            timestamp = System.currentTimeMillis()
                        )
                        handleLiveRoomEvent(joinEvent, userId, roomId, redisService)

                        // Listen for incoming messages
                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Text -> {
                                    val text = frame.readText()
                                    println("DEBUG: RAW WEBSOCKET MESSAGE: $text")

                                    try {
                                        val event = LiveEventJson.decodeFromString<LiveEvent>(text)
                                        println("DEBUG: PARSED EVENT: ${event::class.simpleName}")
                                        handleLiveRoomEvent(event, userId, roomId, redisService, this)
                                    } catch (e: Exception) {
                                        println("DEBUG: PARSE ERROR: ${e.message}")
                                    }
                                }

                                else -> {}
                            }
                        }
                    } finally {
                        // Clean up both local and distributed sessions
                        LiveRoomSessionRegistry.removeSession(this)
                        distributedSessionManager.removeSession(userId, roomId)

                        // Handle leave event
                        val leaveEvent = LiveEvent.LeaveRoom(
                            roomId = roomId,
                            initiatorId = userId,
                            username = username,
                            timestamp = System.currentTimeMillis()
                        )

                        handleLiveRoomEvent(leaveEvent, userId, roomId, redisService)

                        // Check if stream owner is leaving
                        if (distributedPermissionManager.isStreamOwner(roomId, userId)) {
                            val endEvent = LiveEvent.StreamEndedEvent(roomId = roomId)
                            crossInstanceBroadcaster.broadcastToRoom(roomId, endEvent)

                            delay(200L)

                            // Close all local sessions
                            LiveRoomSessionRegistry.getRoomSessions(roomId).forEach { session ->
                                try {
                                    val sessionUserId = LiveRoomSessionRegistry.getUserId(session)

                                    if (sessionUserId != userId) {
                                        val closeReason = CloseReason(CloseReason.Codes.GOING_AWAY, "Stream ended")
                                        session.close(closeReason)
                                    }
                                } catch (e: Exception) {
                                    // ignore
                                } finally {
                                    val sessionUserId = LiveRoomSessionRegistry.getUserId(session)
                                    LiveRoomSessionRegistry.removeSession(session)
                                    if (sessionUserId != null) {
                                        distributedSessionManager.removeSession(sessionUserId, roomId)
                                    }
                                }
                            }

                            // Use distributed permission manager for cleanup
                            distributedPermissionManager.removeRoom(roomId)
                            redisService.deleteCounters(roomId)
                        } else {
                            // Only clean up if room is empty across all instances
                            val roomUsers = distributedSessionManager.getRoomUsers(roomId)
                            if (roomUsers.isEmpty()) {
                                distributedPermissionManager.removeRoom(roomId)
                                redisService.deleteCounters(roomId)
                                sendStreamEndedEvent(roomId)
                            }
                        }
                    }
                }

                /**
                * Authenticated WebSocket endpoint for user notifications.
                * Requires JWT authentication and handles real-time notifications.
                *
                * Path: /ws/notifications (authenticated)
                */
                webSocket("/notifications") {
                    val userId = WebSocketAuthHelper.authenticateUser(
                        call,
                        this,
                        authTokenService
                    )?.toInt() ?: return@webSocket

                    // Register session
                    NotificationSessionRegistry.register(userId, this)

                    suspend fun sendInitialNotificationState(userId: Int) {
                        try {
                            val unreadCount = notificationSchema.getUnreadCount(userId)
                            val recentNotifications = notificationSchema.fetchNotifications(
                                userId,
                                limit = 10,
                                offset = 0
                            )

                            val initialEvent = NotificationEvent.InitialState(
                                userId = userId.toString(),
                                unreadCount = unreadCount,
                                notifications = recentNotifications
                            )

                            val json = NotificationEventJson.encodeToString(
                                notificationEventPolymorphic,
                                initialEvent.withDefaults()
                            )
                            send(Frame.Text(json))
                            println("DEBUG: Sent initial state to user $userId: unreadCount=$unreadCount, notifications=${recentNotifications.size}")
                        } catch (e: Exception) {
                            println("DEBUG: Failed to send initial state to user $userId: ${e.message}")
                        }
                    }

                    try {
                        sendInitialNotificationState(userId)

                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Text -> {
                                    try {
                                        val event =
                                            NotificationEventJson.decodeFromString<NotificationEvent>(
                                                frame.readText()
                                            )
                                        handleNotificationEvent(event, userId, notificationSchema)
                                    } catch (e: Exception) {
                                        println("DEBUG: Error decoding notification event: ${e.message}")
                                    }
                                }

                                else -> {}
                            }
                        }
                    } finally {
                        NotificationSessionRegistry.unregister(userId)
                    }
                }
            }
        }
    }
}


