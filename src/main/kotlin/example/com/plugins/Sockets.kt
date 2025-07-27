package example.com.plugins

import example.com.routes.dtos.LiveEvent
import example.com.schemas.UserSchema
import example.com.services.redis.RedisManager
import example.com.services.token.TokenService
import example.com.services.ws_session.PermissionManager
import example.com.services.ws_session.SessionManager
import io.ktor.server.application.Application
import io.ktor.server.application.install
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
            totalLikes = totalLikes
        )

        redisManager.addToStream(event)
    }

    suspend fun kickUser(roomId: String, targetUserId: String) {
        SessionManager.getSession(roomId, targetUserId)?.let { session ->
            try {
                session.send(Frame.Text(Json.encodeToString(
                    LiveEvent.KickUser(
                        roomId = roomId,
                        initiatorId = "system",
                        targetUserId = targetUserId
                    )
                )))
            } catch (e: Exception) {
                // Connection already closed
            } finally {
                session.close()
                SessionManager.removeSession(session)
                updateStreamStats(roomId, redisManager)
            }
        }
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
            session.send(Frame.Text(Json.encodeToString(event)))
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

        session.send(Frame.Text(Json.encodeToString(event)))
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
            val json = Json.encodeToString(event)
            SessionManager.getRoomSessions(roomId).forEach { session ->
                try {
                    session.send(Frame.Text(json))
                } catch (e: Exception) {
                    SessionManager.removeSession(session)
                }
            }
        }
    }

    suspend fun handleEvent(
        event: LiveEvent,
        userId: String,
        roomId: String,
        redisManager: RedisManager
    ) {
        when (event) {
            is LiveEvent.ChatMessage -> {
                if (!PermissionManager.isMuted(roomId, userId)) {
                    redisManager.addToStream(event)
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
                if (PermissionManager.isStreamOwner(roomId, userId)) {
                    PermissionManager.muteUser(roomId, event.targetUserId)
                    redisManager.addToStream(event)
                }
            }

            is LiveEvent.UnmuteUser -> {
                if (PermissionManager.isStreamOwner(roomId, userId)) {
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
                redisManager.addToStream(event)
            }

            is LiveEvent.Gift -> {
                redisManager.incrementCounter("room:$roomId:gifts:${event.giftId}", event.quantity.toLong())
                redisManager.addToStream(event)
            }

            is LiveEvent.JoinRoom -> {
                // Set stream owner on first join
                if (!PermissionManager.hasStreamOwner(roomId)) {
                    val user = userSchema.findById(userId.toInt())
                    PermissionManager.setStreamOwner(
                        roomId,
                        userId,
                        user?.username ?: "Streamer",
                        user?.imageUrl
                    )

                    // Broadcast stream owner info to all
                    broadcastStreamOwnerInfo(roomId, userSchema)
                }
                redisManager.addToStream(event)
                updateStreamStats(roomId, redisManager)
            }

            is LiveEvent.LeaveRoom -> {
                redisManager.addToStream(event)
                updateStreamStats(roomId, redisManager)
            }

            is LiveEvent.StreamStats -> {
                // System-generated event, no action needed
            }

            is LiveEvent.PublisherInfoEvent -> TODO()
            is LiveEvent.StreamEndedEvent -> TODO()
        }
    }

    routing {
        webSocket("/ws/{roomId}/{userId}") {
            val roomId = call.parameters["roomId"]!!
            val userId = call.parameters["userId"]!!

//            val isStreamer = call.request.queryParameters["isStreamer"]?.toBoolean() ?: false
//            val token = call.request.queryParameters["token"] ?: run {
//                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Missing token"))
//                return@webSocket
//            }
            // Just in case alternate method for5 userId
//            val userId = tokenService.getClaimFromToken(token, "userId") ?: run {
//                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Invalid token"))
//                return@webSocket
//            }

            // Fetch user info from database
            val user = userSchema.findById(userId.toInt())
            val username = user?.username ?: "Unknown"

            // Register session
            SessionManager.addSession(roomId, userId, username,this)

            try {
                // Send initial state
                sendInitialState(roomId, this, redisManager)

                // Send stream owner info if available
                sendStreamOwnerInfo(roomId, this, userSchema)

                // Handle join event
                val joinEvent = LiveEvent.JoinRoom(
                    roomId = roomId,
                    initiatorId = userId,
                    username = username
                )
                handleEvent(joinEvent, userId, roomId, redisManager)

                // Listen for incoming messages
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Text -> {
                            val event = Json.decodeFromString<LiveEvent>(frame.readText())
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
                    LiveEvent.LeaveRoom(roomId, userId),
                    userId,
                    roomId,
                    redisManager
                )

                // Check if room is empty and clean up
                if (SessionManager.getRoomSessions(roomId).isEmpty()) {
                    PermissionManager.removeRoom(roomId)
                    redisManager.deleteCounters(roomId)
                    sendStreamEndedEvent(roomId)
                }
            }
        }
    }
}


