package example.com.plugins

import example.com.LiveEventJson
import example.com.ModerationMessages
import example.com.ModerationReason
import example.com.ModerationSeverity
import example.com.NotificationEventJson
import example.com.ProfileUpdateType
import example.com.routes.dtos.LiveEvent
import example.com.routes.dtos.NotificationEvent
import example.com.routes.dtos.withDefaults
import example.com.schemas.NotificationSchema
import example.com.schemas.UserSchema
import example.com.services.notifications.NotificationSessionRegistry
import example.com.services.redis.RedisService
import example.com.services.token.TokenService
import example.com.services.ws_session.PermissionManager
import example.com.services.ws_session.SessionManager
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
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
import kotlinx.serialization.encodeToString
import java.time.Duration

/**
* Configures WebSocket endpoints for live streaming rooms and notifications.
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
    authTokenService: TokenService
) {
    install(WebSockets) {
        pingPeriod   = Duration.ofSeconds(15)
        timeout      = Duration.ofSeconds(15)
        maxFrameSize = Long.MAX_VALUE
        masking      = false
    }

    // Create polymorphic serializers for event types
    val liveEventPolymorphic = PolymorphicSerializer(LiveEvent::class)
    val notificationEventPolymorphic = PolymorphicSerializer(NotificationEvent::class)

    /**
    * Updates and broadcasts stream statistics (viewer count, total likes) for a room.
    *
    * @param roomId The room ID to update stats for
    * @param redisManager Redis service for storing and broadcasting stats
    */
    fun updateStreamStats(roomId: String, redisManager: RedisService) {
        val viewerCount = SessionManager.getRoomSessions(roomId).size
        val totalLikes = redisManager.getCounter("room:$roomId:likes") ?: 0

        val event = LiveEvent.StreamStats(
            roomId = roomId,
            viewerCount = viewerCount,
            totalLikes = totalLikes,
            timestamp = System.currentTimeMillis()
        )

        redisManager.addToStream(event)
    }

    /**
    * Kicks a user from a room by closing their WebSocket connection and broadcasting the event.
    *
    * @param roomId The room from which to kick the user
    * @param targetUserId The ID of the user to kick
    */
    suspend fun kickUser(roomId: String, targetUserId: String) {
        // Update permission manager to mark user as kicked
        PermissionManager.kickUser(roomId, targetUserId)

        val username = SessionManager.getSessionInfo(roomId, targetUserId)?.username ?: "User"

        // Find and close the user's WebSocket session
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
                SessionManager.removeSession(session)
                updateStreamStats(roomId, redisService)
            }
        }

        // Broadcast system message about the kick
        redisService.addToStream(
            LiveEvent.SystemMessage(
                roomId = roomId,
                text = "$username was kicked from the stream",
                timestamp = System.currentTimeMillis()
            )
        )
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
        val ownerId = PermissionManager.getStreamOwner(roomId) ?: return

        // Get owner info from PermissionManager if available
        val (ownerUsername, ownerAvatar) = PermissionManager.getStreamOwnerInfo(ownerId)
            ?: run {
                // Fallback to database if not in PermissionManager
                val owner = userSchema.findById(ownerId.toInt())
                owner?.username to owner?.imageUrl
            }

        if (ownerUsername != null) {
            val publisherInfoEvent = LiveEvent.PublisherInfoEvent(
                roomId = roomId,
                userId = ownerId,
                username = ownerUsername,
                avatarUrl = ownerAvatar
            )

            val json = LiveEventJson.encodeToString(liveEventPolymorphic, publisherInfoEvent.withDefaults())
            session.send(Frame.Text(json))
        }
    }

    /**
     * Broadcasts stream ended event to all connected clients.
     */
    fun sendStreamEndedEvent(roomId: String) {
        val event = LiveEvent.StreamEndedEvent(roomId = roomId)
        redisService.addToStream(event)
    }

    /**
     * Sends initial room state (viewer count, likes) to a newly connected client.
     */
    suspend fun sendInitialState(
        roomId: String,
        session: WebSocketSession,
        redisManager: RedisService
    ) {
        val viewerCount = SessionManager.getRoomSessions(roomId).size
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
    * Broadcasts stream owner information to all clients in a room.
    * Used when stream owner changes or on room initialization.
    */
    suspend fun broadcastStreamOwnerInfo(
        roomId: String,
        userSchema: UserSchema
    ) {
        val ownerId = PermissionManager.getStreamOwner(roomId) ?: return

        // Get owner info from PermissionManager or database
        val (ownerUsername, ownerAvatar) = PermissionManager.getStreamOwnerInfo(ownerId)
            ?: run {
                val owner = userSchema.findById(ownerId.toInt())
                owner?.username to owner?.imageUrl
            }

        if (ownerUsername != null) {
            val event = LiveEvent.PublisherInfoEvent(
                roomId = roomId,
                userId = ownerId,
                username = ownerUsername,
                avatarUrl = ownerAvatar
            )

            // Broadcast to all sessions in the room
            // Use LiveEventJson for encoding
            val safe = event.withDefaults()
            val json = LiveEventJson.encodeToString(liveEventPolymorphic, safe)

            SessionManager.getRoomSessions(roomId).forEach { session ->
                try {
                    session.send(Frame.Text(json))
                } catch (e: Exception) {
                    SessionManager.removeSession(session)
                }
            }
        }
    }

    /**
    * Sends a system message to all clients in a room.
    */
    suspend fun sendSystemMessage(
        roomId: String,
        text: String,
        redisManager: RedisService
    ) {
        val event = LiveEvent.SystemMessage(
            roomId = roomId,
            text = text,
            timestamp = System.currentTimeMillis()
        )
        redisManager.addToStream(event)
    }

    /**
    * Main handler for live room events. Processes different types of events
    * and applies appropriate business logic and permissions.
    *
    * Events flow: Client → WebSocket → handleLiveRoomEvent → Redis Stream → All Clients
    */
    suspend fun handleLiveRoomEvent(
        event: LiveEvent,
        userId: String,
        roomId: String,
        redisManager: RedisService
    ) {
        // Ignore any client events if the user is kicked.
        if (PermissionManager.isKicked(roomId, userId) && event.initiatorId != "system") {
            return
        }

        when (event) {
            is LiveEvent.ChatMessage, is LiveEvent.SystemMessage -> {
                redisManager.addToHistory(roomId, event)

                when (event) {
                    is LiveEvent.ChatMessage -> {
                        if (event.initiatorId == "system" || !PermissionManager.isMuted(roomId, userId)) {
                            redisManager.addToStream(event)
                        }
                    }
                    else -> redisManager.addToStream(event)
                }
            }

            is LiveEvent.KickUser -> {
                if (PermissionManager.isStreamOwner(roomId, userId) ||
                    PermissionManager.isModerator(roomId, userId)) {

                    kickUser(roomId, event.targetUserId)
                    redisManager.addToStream(event)
                }
            }

            is LiveEvent.MuteUser -> {
                // Allow both stream owner AND moderators to mute users
                if (PermissionManager.isStreamOwner(roomId, userId) ||
                    PermissionManager.isModerator(roomId, userId)) {

                    PermissionManager.muteUser(roomId, event.targetUserId)
                    redisManager.addToStream(event)
                }
            }

            is LiveEvent.UnmuteUser -> {
                // Allow both stream owner AND moderators to unmute users
                if (PermissionManager.isStreamOwner(roomId, userId) ||
                    PermissionManager.isModerator(roomId, userId)) {

                    PermissionManager.unmuteUser(roomId, event.targetUserId)
                    redisManager.addToStream(event)
                }
            }

            is LiveEvent.GrantModerator -> {
                // Only stream owners can manage moderators
                if (PermissionManager.isStreamOwner(roomId, userId)) {
                    PermissionManager.grantModerator(roomId, event.targetUserId)
                    redisManager.addToStream(event)
                }
            }

            is LiveEvent.RevokeModerator -> {
                // Only stream owners can manage moderators
                if (PermissionManager.isStreamOwner(roomId, userId)) {
                    PermissionManager.revokeModerator(roomId, event.targetUserId)
                    redisManager.addToStream(event)
                }
            }

            is LiveEvent.Like -> {
                // Increment room and user like counters
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

                    sendSystemMessage(roomId, message, redisManager)
                }

                redisManager.addToStream(event)
            }

            is LiveEvent.Gift -> {
                // Track gift counts by gift type
                redisManager.incrementCounter("room:$roomId:gifts:${event.giftId}", event.quantity.toLong())
                redisManager.addToStream(event)
            }

            is LiveEvent.JoinRoom -> {
                // First user to join becomes stream owner
                if (!PermissionManager.hasStreamOwner(roomId)) {
                    val user = userSchema.findById(userId.toInt())
                    PermissionManager.setStreamOwner(
                        roomId,
                        userId,
                        user?.username ?: "Streamer",
                        user?.imageUrl
                    )

                    broadcastStreamOwnerInfo(roomId, userSchema)
                }

                val systemMessage = LiveEvent.SystemMessage(
                    roomId = roomId,
                    text = "${event.username} joined the stream"
                )

                redisManager.addToStream(systemMessage)
                redisManager.addToStream(event)
                updateStreamStats(roomId, redisManager)
            }

            is LiveEvent.LeaveRoom -> {
                // Update stats when user leaves
                val session = SessionManager.getSession(roomId, userId)
                val username = session?.let { SessionManager.getUsername(it) } ?: "User"

                val systemMessage = LiveEvent.SystemMessage(
                    roomId = roomId,
                    text = "$username left the stream"
                )

                // Add to stream and update stats
//                redisManager.addToStream(systemMessage)
                updateStreamStats(roomId, redisManager)
            }

            is LiveEvent.StreamStats -> {
                // System-generated event, no action needed
            }

            is LiveEvent.PublisherInfoEvent -> {
                // Handle if needed, or leave empty
            }

            is LiveEvent.StreamEndedEvent -> {
                // Handle if needed
            }

            is LiveEvent.ModerationWarningEvent -> {
                // Broadcast moderation warnings with user-specific messages
                val sessions = SessionManager.getRoomSessions(roomId)
                val streamOwnerId = PermissionManager.getStreamOwner(roomId)

                sessions.forEach { session ->
                    try {
                        val sessionUserId = SessionManager.getUserId(session)
                        val isStreamer = sessionUserId == streamOwnerId

                        // Customize message based on user role and severity
                        val reason = try {
                            ModerationReason.valueOf(event.reason.uppercase())
                        } catch (e: Exception) { ModerationReason.OTHER }
                        val severity = try {
                            ModerationSeverity.valueOf(event.severity.uppercase())
                        } catch (e: Exception) { ModerationSeverity.WARNING }

                        // Different messages for streamer vs viewers
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

                // Also persist the event in history
                redisManager.addToStream(event)
            }

            is LiveEvent.StreamTerminatedEvent -> {
                // Broadcast to all sessions in the room (both publisher and consumers)
                redisManager.addToStream(event)

                val sessions = SessionManager.getRoomSessions(roomId)
                val streamOwnerId = PermissionManager.getStreamOwner(roomId)

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
                    }
                }

                PermissionManager.removeRoom(roomId)
                redisManager.deleteCounters(roomId)
            }

            is LiveEvent.ModerationClearEvent -> {
                // Broadcast clear event to all sessions in the room
                redisManager.addToStream(event)

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
                    val json = NotificationEventJson.encodeToString(updateEvent)
                    NotificationSessionRegistry.sendToUser(userId, json)
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
            /**
            * WebSocket endpoint for live streaming rooms.
            * Handles real-time events like chat, likes, moderation, etc.
            *
            * Path: /ws/liveRoom/{roomId}/{userId}
            */
            webSocket("/liveRoom/{roomId}/{userId}") {
                val roomId = call.parameters["roomId"]!!
                val userId = call.parameters["userId"]!!
                val user = userSchema.findById(userId.toInt())
                val username = user?.username ?: "Unknown"

                // Check if session already exists and remove it first
                SessionManager.getSession(roomId, userId)?.let { existingSession ->
                    try {
                        existingSession.close(CloseReason(CloseReason.Codes.NORMAL, "Reconnecting"))
                    } catch (e: Exception) {
                        // Ignore
                    } finally {
                        SessionManager.removeSession(existingSession)
                    }
                }

                // Check if user is kicked
                if (PermissionManager.isKicked(roomId, userId)) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "kicked"))
                    return@webSocket
                }

                // Register session
                SessionManager.addSession(roomId, userId, username, this)

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
                                val event =
                                    LiveEventJson.decodeFromString<LiveEvent>(frame.readText())
                                handleLiveRoomEvent(event, userId, roomId, redisService)
                            }

                            else -> {}
                        }
                    }
                } finally {
                    // Remove session first for accurate count
                    SessionManager.removeSession(this)

                    // Handle leave event
                    handleLiveRoomEvent(
                        LiveEvent.LeaveRoom(
                            roomId = roomId,
                            initiatorId = userId,
                            username = username,
                            timestamp = System.currentTimeMillis()
                        ),
                        userId,
                        roomId,
                        redisService
                    )

                    // Check if stream owner is leaving
                    if (PermissionManager.isStreamOwner(roomId, userId)) {
                        val endEvent = LiveEvent.StreamEndedEvent(roomId = roomId)
                        redisService.addToStream(endEvent)

                        delay(200L)

                        SessionManager.getRoomSessions(roomId).forEach { session ->
                            try {
                                session.close(
                                    CloseReason(
                                        CloseReason.Codes.GOING_AWAY,
                                        "Stream ended"
                                    )
                                )
                            } catch (e: Exception) {
                                // ignore
                            }

                            SessionManager.removeSession(session)
                        }

                        // Clean up room state
                        PermissionManager.removeRoom(roomId)
                        redisService.deleteCounters(roomId)
                    } else {
                        // Only clean up if room is empty
                        if (SessionManager.getRoomSessions(roomId).isEmpty()) {
                            PermissionManager.removeRoom(roomId)
                            redisService.deleteCounters(roomId)
                            sendStreamEndedEvent(roomId)
                        }
                    }
                }
            }

            authenticate("auth-jwt") {
                /**
                * Authenticated WebSocket endpoint for user notifications.
                * Requires JWT authentication and handles real-time notifications.
                *
                * Path: /ws/notifications (authenticated)
                */
                webSocket("/notifications") {
                    val principal = call.principal<JWTPrincipal>() ?: run {
                        println("DEBUG: No JWT principal found")
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthenticated"))
                        return@webSocket
                    }

                    val idStr =
                        authTokenService.getClaim(principal, "userId") ?: authTokenService.getClaim(
                            principal,
                            "sub"
                        )
                    val userId = idStr?.toIntOrNull() ?: run {
                        println("DEBUG: Invalid user id: $idStr")
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid user id"))
                        return@webSocket
                    }

                    println("DEBUG: WebSocket connected for user $userId")

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
            // Keep /liveRoom for now (but plan to split into chat/control)
        }
    }
}


