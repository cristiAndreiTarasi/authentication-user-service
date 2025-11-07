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
import example.com.services.ws_session.SessionManager
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
    val instanceId = serviceManager.instanceId

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
    * Kicks a user from a room by closing their WebSocket connection and broadcasting the event.
    *
    * @param roomId The room from which to kick the user
    * @param targetUserId The ID of the user to kick
    */
    suspend fun kickUser(roomId: String, targetUserId: String) {
        // Update permission manager to mark user as kicked
        distributedPermissionManager.kickUser(roomId, targetUserId)

        // Get username from distributed session manager
        val sessionInfo = distributedSessionManager.getSessionInfo(targetUserId, roomId)
        val username = sessionInfo?.get("username") ?: "User"

        //Find and close the user's WebSocket session across all instances
        // Note: We can only close local sessions, remote sessions will be handled via cross-instance messaging
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
                updateStreamStats(roomId, redisService)
            }
        }

        // Broadcast system message about the kick using cross-instance broadcaster
        val systemMessage = LiveEvent.SystemMessage(
            roomId = roomId,
            text = "$username was kicked from the stream",
            timestamp = System.currentTimeMillis()
        )
        crossInstanceBroadcaster.broadcastToRoom(roomId, systemMessage)
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
        val ownerInfo = distributedPermissionManager.getStreamOwnerInfo(roomId)
        val ownerUsername = ownerInfo?.get("username")
        val ownerAvatar = ownerInfo?.get("avatarUrl")?.takeIf { it.isNotBlank() }

        // We need a non-null username for PublisherInfoEvent
        val finalUsername = if (ownerUsername != null) {
            ownerUsername
        } else {
            // Fallback to database lookup
            val owner = userSchema.findById(ownerId.toInt())
            owner?.username ?: "Streamer" // Provide default if still null
        }

        val publisherInfoEvent = LiveEvent.PublisherInfoEvent(
            roomId = roomId,
            userId = ownerId,
            username = finalUsername, // Now guaranteed non-null
            avatarUrl = ownerAvatar
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

        val localSessions = SessionManager.getRoomSessions(roomId).toList()
        if (localSessions.isNotEmpty()) {
            localSessions.forEach { session ->
                try {
                    val closeReason = CloseReason(CloseReason.Codes.GOING_AWAY, "Stream ended by publisher")
                    session.close(closeReason)
                } catch (e: Exception) {
                    println("DEBUG: Error closing session: ${e.message}")
                } finally {
                    val sessionUserId = SessionManager.getUserId(session)
                    SessionManager.removeSession(session)
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
    * Main handler for live room events. Processes different types of events
    * with clear separation between real-time (Pub/Sub) and durable (Streams) processing.
    *
    * Updated to use Pub/Sub for real-time delivery and Streams for durable workflows
    */
    suspend fun handleLiveRoomEvent(
        event: LiveEvent,
        userId: String,
        roomId: String,
        redisManager: RedisService
    ) {
        // Ignore any client events if the user is kicked.
        if (distributedPermissionManager.isKicked(roomId, userId) && event.initiatorId != "system") {
            return
        }

        when (event) {
            // REAL-TIME EVENTS (Pub/Sub only)
            is LiveEvent.Like -> {
                // Real-time: Increment counters and broadcast instantly
                redisManager.incrementCounter("room:$roomId:likes", event.count.toLong())
                val userTotal = redisManager.incrementUserLikeCount(roomId, userId, event.count.toLong())

                // Send milestone messages for user like counts
                if (userTotal == 1L || userTotal % 100 == 0L) {
                    val sessionInfo = SessionManager.getSessionInfo(roomId, userId)
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
                    // Real-time: Use Pub/Sub for system messages
                    crossInstanceBroadcaster.broadcastToRoom(roomId, systemEvent)
                }

                // Real-time: Broadcast like event via Pub/Sub
                crossInstanceBroadcaster.broadcastToRoom(roomId, event)

                // Durable: Only persist significant like activity for analytics
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

                // Real-time: Broadcast both join event and system message
                crossInstanceBroadcaster.broadcastToRoom(roomId, event)
                crossInstanceBroadcaster.broadcastToRoom(roomId, systemMessage)

                // Update computed stats
                updateStreamStats(roomId, redisManager)
            }

            is LiveEvent.LeaveRoom -> {
                val systemMessage = LiveEvent.SystemMessage(
                    roomId = roomId,
                    text = "${event.username} left the stream",
                    timestamp = System.currentTimeMillis()
                )

                // Real-time: Broadcast both leave event and system message
                crossInstanceBroadcaster.broadcastToRoom(roomId, event)
                crossInstanceBroadcaster.broadcastToRoom(roomId, systemMessage)

                // Update computed stats
                updateStreamStats(roomId, redisManager)
            }

            // HYBRID EVENTS (Real-time + Durable)
            is LiveEvent.ChatMessage -> {
                // Durable: Persist for moderation and analytics BEFORE real-time delivery
                redisManager.addToModerationStream(event)
                redisManager.addToAnalyticsStream(event)

                // Real-time: Deliver via Pub/Sub if not muted
                if (!distributedPermissionManager.isMuted(roomId, userId)) {
                    crossInstanceBroadcaster.broadcastToRoom(roomId, event)
                }

                // Limited retention: Add to room history for replay
                redisManager.addToHistory(roomId, event)
            }

            is LiveEvent.Gift -> {
                // Real-time: Instant celebration via Pub/Sub
                crossInstanceBroadcaster.broadcastToRoom(roomId, event)

                // Durable: Critical for billing and revenue tracking
                redisManager.addToBillingStream(event)
                redisManager.addToAnalyticsStream(event)

                // Track gift counts
                redisManager.incrementCounter("room:$roomId:gifts:${event.giftId}", event.quantity.toLong())
            }

            // MODERATION EVENTS (Real-time + Durable Audit)
            is LiveEvent.KickUser -> {
                if (distributedPermissionManager.isStreamOwner(roomId, userId) ||
                    distributedPermissionManager.isModerator(roomId, userId)) {

                    kickUser(roomId, event.targetUserId)
                    // Real-time: Broadcast action via Pub/Sub
                    crossInstanceBroadcaster.broadcastToRoom(roomId, event)
                    // Durable: Audit log for moderation actions
                    redisManager.addToModerationStream(event)
                }
            }

            is LiveEvent.MuteUser -> {
                if (distributedPermissionManager.isStreamOwner(roomId, userId) ||
                    distributedPermissionManager.isModerator(roomId, userId)) {

                    distributedPermissionManager.muteUser(roomId, event.targetUserId)
                    // Real-time: Broadcast action
                    crossInstanceBroadcaster.broadcastToRoom(roomId, event)
                    // Durable: Audit log
                    redisManager.addToModerationStream(event)
                }
            }

            is LiveEvent.UnmuteUser -> {
                if (distributedPermissionManager.isStreamOwner(roomId, userId) ||
                    distributedPermissionManager.isModerator(roomId, userId)) {

                    distributedPermissionManager.unmuteUser(roomId, event.targetUserId)
                    crossInstanceBroadcaster.broadcastToRoom(roomId, event)
                    redisManager.addToModerationStream(event)
                }
            }

            is LiveEvent.GrantModerator -> {
                if (distributedPermissionManager.isStreamOwner(roomId, userId)) {
                    distributedPermissionManager.grantModerator(roomId, event.targetUserId)
                    crossInstanceBroadcaster.broadcastToRoom(roomId, event)
                    redisManager.addToModerationStream(event)
                }
            }

            is LiveEvent.RevokeModerator -> {
                if (distributedPermissionManager.isStreamOwner(roomId, userId)) {
                    distributedPermissionManager.revokeModerator(roomId, event.targetUserId)
                    crossInstanceBroadcaster.broadcastToRoom(roomId, event)
                    redisManager.addToModerationStream(event)
                }
            }

            // SYSTEM EVENTS (Mostly Real-time)
            is LiveEvent.SystemMessage -> {
                // Real-time: Broadcast via Pub/Sub
                crossInstanceBroadcaster.broadcastToRoom(roomId, event)
                // Durable: Only persist important system messages
                if (event.text.contains("violation", ignoreCase = true) ||
                    event.text.contains("terminated", ignoreCase = true)) {
                    redisManager.addToModerationStream(event)
                }
            }

            // COMPUTED STATE (Real-time only)
            is LiveEvent.StreamStats, is LiveEvent.PublisherInfoEvent -> {
                // Real-time: Computed state - no persistence needed
                crossInstanceBroadcaster.broadcastToRoom(roomId, event)
            }

            // MODERATION WARNINGS (Real-time + Durable)
            is LiveEvent.ModerationWarningEvent -> {
                // Real-time: Customized delivery to each user
                val streamOwnerId = distributedPermissionManager.getStreamOwner(roomId)
                val sessions = SessionManager.getRoomSessions(roomId)

                sessions.forEach { session ->
                    try {
                        val sessionUserId = SessionManager.getUserId(session)
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

                // Durable: Persist moderation warnings
                redisManager.addToModerationStream(event)
            }

            is LiveEvent.StreamTerminatedEvent -> {
                // Real-time: Broadcast to all sessions
                crossInstanceBroadcaster.broadcastToRoom(roomId, event)

                val streamOwnerId = distributedPermissionManager.getStreamOwner(roomId)

                // Close all local sessions with customized messages
                val sessions = SessionManager.getRoomSessions(roomId)
                sessions.forEach { session ->
                    try {
                        val sessionUserId = SessionManager.getUserId(session)
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

                // Close all sessions
                sessions.forEach { session ->
                    try {
                        session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Stream terminated"))
                    } catch (e: Exception) {
                        // ignore
                    } finally {
                        SessionManager.removeSession(session)
                        distributedSessionManager.removeSession(SessionManager.getUserId(session) ?: "", roomId)
                    }
                }

                distributedPermissionManager.removeRoom(roomId)
                redisManager.deleteCounters(roomId)

                // Durable: Persist stream termination
                redisManager.addToModerationStream(event)
            }

            is LiveEvent.ModerationClearEvent -> {
                // Real-time: Broadcast clear event
                crossInstanceBroadcaster.broadcastToRoom(roomId, event)

                // Also send immediate clear to all connected sessions
                val sessions = SessionManager.getRoomSessions(roomId)
                sessions.forEach { session ->
                    try {
                        val json = LiveEventJson.encodeToString(liveEventPolymorphic, event.withDefaults())
                        session.send(Frame.Text(json))
                    } catch (e: Exception) {
                        // ignore send failure
                    }
                }

                // Durable: Persist moderation clear action
                redisManager.addToModerationStream(event)
            }

            is LiveEvent.StreamEndedEvent -> {
                // Find and close the user's WebSocket session
                SessionManager.getSession(roomId, userId)?.let { session ->
                    try {
                        val closeReason = CloseReason(CloseReason.Codes.GOING_AWAY, "Stream ended")
                        session.close(closeReason)
                    } catch (e: Exception) {
                        println("DEBUG: Error closing session on StreamEndedEvent: ${e.message}")
                    } finally {
                        SessionManager.removeSession(session)
                        distributedSessionManager.removeSession(userId, roomId)
                    }
                }

                // Also update stats to reflect the user leaving
                updateStreamStats(roomId, redisManager)
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

                    // Check if session exists in distributed manager and handle reconnection
                    if (distributedSessionManager.isUserInRoom(userId, roomId)) {
                        // User already has a session somewhere - close existing local session if any
                        SessionManager.getSession(roomId, userId)?.let { existingSession ->
                            try {
                                existingSession.close(CloseReason(CloseReason.Codes.NORMAL, "Reconnecting"))
                            } catch (e: Exception) {
                                // Ignore
                            } finally {
                                SessionManager.removeSession(existingSession)
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
                    SessionManager.addSession(roomId, userId, username, this)
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
                                    // Use LiveEventJson for decoding
                                    val event = LiveEventJson.decodeFromString<LiveEvent>(frame.readText())
                                    handleLiveRoomEvent(event, userId, roomId, redisService)
                                }

                                else -> {}
                            }
                        }
                    } finally {
                        // Clean up both local and distributed sessions
                        SessionManager.removeSession(this)
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
                            SessionManager.getRoomSessions(roomId).forEach { session ->
                                try {
                                    val sessionUserId = SessionManager.getUserId(session)

                                    if (sessionUserId != userId) {
                                        val closeReason = CloseReason(CloseReason.Codes.GOING_AWAY, "Stream ended")
                                        session.close(closeReason)
                                    }
                                } catch (e: Exception) {
                                    // ignore
                                } finally {
                                    val sessionUserId = SessionManager.getUserId(session)
                                    SessionManager.removeSession(session)
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


