package example.com.plugins

import example.com.LiveEventJson
import example.com.routes.dtos.LiveEvent
import example.com.schemas.UserSchema
import example.com.services.redis.RedisManager
import example.com.services.token.TokenService
import example.com.services.ws_session.PermissionManager
import example.com.services.ws_session.SessionManager
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.response.respond
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Duration

fun Application.configureSockets(
    redisManager: RedisManager,
    userSchema: UserSchema,
    tokenService: TokenService
) {
    install(WebSockets) {
        pingPeriod   = Duration.ofSeconds(15)
        timeout      = Duration.ofSeconds(15)
        maxFrameSize = Long.MAX_VALUE
        masking      = false
    }

    fun updateStreamStats(roomId: String, redisManager: RedisManager) {
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

    suspend fun kickUser(roomId: String, targetUserId: String) {
        PermissionManager.kickUser(roomId, targetUserId)

        val username = SessionManager.getSessionInfo(roomId, targetUserId)?.username ?: "User"

        SessionManager.getSession(roomId, targetUserId)?.let { session ->
            try {
                // Use LiveEventJson instead of Json
                session.send(Frame.Text(LiveEventJson.encodeToString(
                    LiveEvent.KickUser(
                        roomId = roomId,
                        initiatorId = "system",
                        targetUserId = targetUserId,
                        timestamp = System.currentTimeMillis()
                    )
                )))

                session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "kicked"))
            } catch (e: Exception) {
                // Connection already closed
            } finally {
                SessionManager.removeSession(session)
                updateStreamStats(roomId, redisManager)
            }
        }

        redisManager.addToStream(
            LiveEvent.SystemMessage(
                roomId = roomId,
                text = "$username was kicked from the stream",
                timestamp = System.currentTimeMillis()
            )
        )
    }

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
            val event = LiveEvent.PublisherInfoEvent(
                roomId = roomId,
                userId = ownerId,
                username = ownerUsername,
                avatarUrl = ownerAvatar
            )
            // Use LiveEventJson for encoding
            session.send(Frame.Text(LiveEventJson.encodeToString(event)))
        }
    }

    fun sendStreamEndedEvent(roomId: String) {
        val event = LiveEvent.StreamEndedEvent(roomId = roomId)
        redisManager.addToStream(event)
    }

    suspend fun sendInitialState(
        roomId: String,
        session: WebSocketSession,
        redisManager: RedisManager
    ) {
        val viewerCount = SessionManager.getRoomSessions(roomId).size
        val totalLikes = redisManager.getCounter("room:$roomId:likes") ?: 0

        val event = LiveEvent.StreamStats(
            roomId = roomId,
            viewerCount = viewerCount,
            totalLikes = totalLikes
        )

        // Use LiveEventJson for encoding
        session.send(Frame.Text(LiveEventJson.encodeToString(event)))
    }

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
            val json = LiveEventJson.encodeToString(event)
            SessionManager.getRoomSessions(roomId).forEach { session ->
                try {
                    session.send(Frame.Text(json))
                } catch (e: Exception) {
                    SessionManager.removeSession(session)
                }
            }
        }
    }

    suspend fun sendSystemMessage(
        roomId: String,
        text: String,
        redisManager: RedisManager
    ) {
        val event = LiveEvent.SystemMessage(
            roomId = roomId,
            text = text,
            timestamp = System.currentTimeMillis()
        )
        redisManager.addToStream(event)
    }

    suspend fun handleEvent(
        event: LiveEvent,
        userId: String,
        roomId: String,
        redisManager: RedisManager
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
                if (PermissionManager.isStreamOwner(roomId, userId)) {
                    PermissionManager.grantModerator(roomId, event.targetUserId)
                    redisManager.addToStream(event)
                }
            }

            is LiveEvent.RevokeModerator -> {
                if (PermissionManager.isStreamOwner(roomId, userId)) {
                    PermissionManager.revokeModerator(roomId, event.targetUserId)
                    redisManager.addToStream(event)
                }
            }

            is LiveEvent.Like -> {
                redisManager.incrementCounter("room:$roomId:likes", event.count.toLong())
                val userTotal = redisManager.incrementUserLikeCount(roomId, userId, event.count.toLong())

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
                redisManager.incrementCounter("room:$roomId:gifts:${event.giftId}", event.quantity.toLong())
                redisManager.addToStream(event)
            }

            is LiveEvent.JoinRoom -> {
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
        }
    }

    routing {
        webSocket("/ws/{roomId}/{userId}") {
            val roomId = call.parameters["roomId"]!!
            val userId = call.parameters["userId"]!!
            val user = userSchema.findById(userId.toInt())
            val username = user?.username ?: "Unknown"

            // Check if user is kicked
            if (PermissionManager.isKicked(roomId, userId)) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "kicked"))
                return@webSocket
            }

            // Register session
            SessionManager.addSession(roomId, userId, username,this)

            try {
                // Send chat history first
                val history = redisManager.getRoomHistory(roomId)
                history.forEach { event ->
                    send(Frame.Text(LiveEventJson.encodeToString(event)))
                }

                // Send initial state
                sendInitialState(roomId, this, redisManager)

                // Send stream owner info if available
                sendStreamOwnerInfo(roomId, this, userSchema)

                // Handle join event
                val joinEvent = LiveEvent.JoinRoom(
                    roomId = roomId,
                    initiatorId = userId,
                    username = username,
                    timestamp = System.currentTimeMillis()
                )
                handleEvent(joinEvent, userId, roomId, redisManager)

                // Listen for incoming messages
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Text -> {
                            // Use LiveEventJson for decoding
                            val event = LiveEventJson.decodeFromString<LiveEvent>(frame.readText())
                            handleEvent(event, userId, roomId, redisManager)
                        }
                        else -> {}
                    }
                }
            } finally {
                // Remove session first for accurate count
                SessionManager.removeSession(this)

                // Handle leave event
                handleEvent(
                    LiveEvent.LeaveRoom(
                        roomId = roomId,
                        initiatorId = userId,
                        username = username,
                        timestamp = System.currentTimeMillis()
                    ),
                    userId,
                    roomId,
                    redisManager
                )

                // Check if stream owner is leaving
                if (PermissionManager.isStreamOwner(roomId, userId)) {
                    val endEvent = LiveEvent.StreamEndedEvent(roomId = roomId)
                    redisManager.addToStream(endEvent)

                    delay(200L)

                    SessionManager.getRoomSessions(roomId).forEach { session ->
                        try {
                            session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Stream ended"))
                        } catch (e: Exception) {
                            // ignore
                        }

                        SessionManager.removeSession(session)
                    }

                    // Clean up room state
                    PermissionManager.removeRoom(roomId)
                    redisManager.deleteCounters(roomId)
                } else {
                    // Only clean up if room is empty
                    if (SessionManager.getRoomSessions(roomId).isEmpty()) {
                        PermissionManager.removeRoom(roomId)
                        redisManager.deleteCounters(roomId)
                        sendStreamEndedEvent(roomId)
                    }
                }
            }
        }
    }
}


