package example.com.services.socket

import example.com.routes.dtos.PresenceUpdateDto
import example.com.routes.dtos.VisitorUpdateDto
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

// A global manager for tracking WebSocket sessions per stream.
object VisitorsManager {
    val sessions = ConcurrentHashMap<String, MutableSet<WebSocketSession>>() // streamId -> sessions
    private val publishers = ConcurrentHashMap<String, WebSocketSession>()

    fun addSession(streamId: String, session: WebSocketSession) {
        sessions.computeIfAbsent(streamId) { mutableSetOf() }.add(session)
    }

    fun removeSession(streamId: String, session: WebSocketSession) {
        sessions[streamId]?.remove(session)
    }

    fun setPublisher(streamId: String, session: WebSocketSession) {
        publishers[streamId] = session
    }

    fun removePublisher(streamId: String) {
        publishers.remove(streamId)
    }

    suspend fun broadcastVisitorUpdate(streamId: String, update: VisitorUpdateDto) {
        broadcastJson(streamId, update)
    }

    suspend fun broadcastPresence(streamId: String, update: PresenceUpdateDto) {
        broadcastJson(streamId, update)
    }

    suspend fun broadcastChat(streamId: String, senderId: String, message: String) {
        // You can wrap it in a simple map or DTO; here we use a map for simplicity.
        val data = mapOf("type" to "chat", "senderId" to senderId, "message" to message)
        broadcastJson(streamId, data)
    }

    suspend fun broadcastLike(streamId: String, userId: String) {
        val data = mapOf("type" to "like", "userId" to userId)
        broadcastJson(streamId, data)
    }

    suspend fun sendControl(streamId: String, targetUserId: String, message: String) {
        sessions[streamId]?.forEach { session ->
            // Optionally, send control messages directly to a user;
            // here we broadcast, but you might choose to send only to the target.
            try {
                session.send(Frame.Text(Json.encodeToString(mapOf("type" to "control", "targetUserId" to targetUserId, "message" to message))))
            } catch (e: Exception) {
                println("Error sending control: ${e.message}")
            }
        }
    }

    suspend fun kickUser(streamId: String, targetUserId: String) {
        val userSession = sessions[streamId]?.firstOrNull { s ->
            // Assume we can match session's userId via a custom property or a maintained map.
            // For simplicity, this example doesn't track userId per session.
            false
        }
        userSession?.close(CloseReason(CloseReason.Codes.NORMAL, "You were kicked"))
    }

    suspend inline fun <reified T> broadcastJson(streamId: String, data: T) {
        val text = Json.encodeToString(data)
        sessions[streamId]?.forEach { session ->
            try {
                session.send(Frame.Text(text))
            } catch (e: Exception) {
                println("Failed to send frame: ${e.message}")
            }
        }
    }
}


