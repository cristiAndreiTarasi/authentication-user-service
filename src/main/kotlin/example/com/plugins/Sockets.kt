package example.com.plugins

import example.com.StreamAction
import example.com.UserRole
import example.com.config.jsonFormat
import example.com.routes.dtos.PresenceUpdateDto
import example.com.routes.dtos.SocketAction
import example.com.routes.dtos.StreamSocketEvent
import example.com.routes.dtos.VisitorDto
import example.com.services.redis.VisitorCache
import example.com.services.role.RoleService
import example.com.services.socket.VisitorsManager
import example.com.services.token.TokenService
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.serialization.json.Json
import java.time.Duration

// Server: Configure WebSocket and use shared types
fun Application.configureSockets(
    tokenService: TokenService,
    roleService: RoleService
) {
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(15)
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    routing {
        webSocket("/ws/stream/{streamId}") {
            val streamId = call.parameters["streamId"] ?: run {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Missing streamId"))
                return@webSocket
            }
            // Get token & userId from query parameters
            val token = call.request.queryParameters["token"]
            val userId = token?.let { tokenService.getClaimFromToken(it, "userId") }
            if (userId == null) {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Invalid token"))
                return@webSocket
            }

            // Determine role using the JWT principal if available
            val principal = call.principal<JWTPrincipal>()
            val globalRole = principal?.let { tokenService.getClaim(it, "role") }
            val scopedRole: String = when (globalRole) {
                UserRole.OWNER.roleName -> UserRole.PUBLISHER.roleName
                else -> roleService.getScopedRole(streamId, userId) ?: UserRole.VISITOR.roleName
            }
            println("User $userId connecting to stream $streamId with role $scopedRole")

            // Register publisher/visitor sessions
            if (scopedRole == UserRole.PUBLISHER.roleName) {
                VisitorsManager.setPublisher(streamId, this)
            }
            VisitorsManager.addSession(streamId, this)

            // Only update visitor count if the user is not a publisher
            if (scopedRole != UserRole.PUBLISHER.roleName) {
                val visitor = VisitorDto(userId)
                val updated = VisitorCache().incrementVisitorCount(streamId, visitor)
                val updateEvent = StreamSocketEvent.VisitorUpdate(updated)
                // Broadcast the visitor update event using the shared jsonFormat
                VisitorsManager.broadcastJson(streamId, updateEvent)
                // Optionally send an initial message to this connection
                send(Frame.Text(jsonFormat.encodeToString(StreamSocketEvent.serializer(), updateEvent)))
            }

            // Broadcast presence event; you may choose to also wrap it in a polymorphic event if needed.
            VisitorsManager.broadcastPresence(streamId, PresenceUpdateDto(userId, joined = true, timestamp = System.currentTimeMillis()))

            try {
                // Listen for incoming messages (chat, likes, etc.)
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val message = frame.readText()
                        try {
                            // Here you may decode an action message. For simplicity, assume it’s a JSON object with an "action" field.
                            // (Alternatively, you can make this polymorphic too.)
                            val actionMsg = Json.decodeFromString<SocketAction>(message)
                            when (actionMsg.action) {
                                StreamAction.CHAT.actionName -> {
                                    VisitorsManager.broadcastChat(streamId, userId, actionMsg.payload ?: "")
                                }
                                StreamAction.LIKE.actionName -> {
                                    VisitorsManager.broadcastLike(streamId, userId)
                                }
                                StreamAction.MUTE.actionName, StreamAction.BAN.actionName -> {
                                    if (scopedRole == UserRole.MODERATOR.roleName || scopedRole == UserRole.PUBLISHER.roleName) {
                                        actionMsg.targetUserId?.let { target ->
                                            VisitorsManager.sendControl(streamId, target, "${actionMsg.action}d by $userId")

                                            if (actionMsg.action == StreamAction.BAN.actionName) {
                                                VisitorsManager.kickUser(streamId, target)
                                            }
                                        }
                                    } else {
                                        println("User $userId is not authorized to perform ${actionMsg.action}")
                                    }
                                }
                                else -> println("Unknown message type")
                            }
                        } catch (e: Exception) {
                            println("Failed to parse message: $message, error: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                println("WebSocket error: ${e.message}")
            } finally {
                // Always remove the session on disconnect.
                VisitorsManager.removeSession(streamId, this)
                VisitorsManager.broadcastPresence(
                    streamId,
                    PresenceUpdateDto(userId, joined = false, timestamp = System.currentTimeMillis())
                )
                if (scopedRole == UserRole.PUBLISHER.roleName) {
                    VisitorsManager.removePublisher(streamId)
                    VisitorCache().clearStream(streamId)
                } else {
                    val updatedOnLeave = VisitorCache().decrementVisitorCount(streamId, VisitorDto(userId))
                    val leaveEvent = StreamSocketEvent.VisitorUpdate(updatedOnLeave)
                    VisitorsManager.broadcastJson(streamId, leaveEvent)
                }
            }
        }
    }
}


