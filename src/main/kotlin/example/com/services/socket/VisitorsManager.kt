package example.com.services.socket

import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class VisitorUpdateDto(
    val visitorCount: Int,
    val visitorList: List<VisitorDto>
)

@Serializable
data class VisitorDto(
    val userId: String,
//    val name: String,
//    val avatarUrl: String?
)

// A global manager for tracking WebSocket sessions per stream.
object VisitorsManager {
    private val sessions = ConcurrentHashMap<String, MutableSet<WebSocketSession>>()

    fun addSession(streamId: String, session: WebSocketSession) {
        sessions.computeIfAbsent(streamId) { mutableSetOf() }.add(session)
    }

    fun removeSession(streamId: String, session: WebSocketSession) {
        sessions[streamId]?.remove(session)
    }

    // Broadcast a visitor update (converted to a DTO) to all connected sessions for the given stream.
    suspend fun broadcastVisitorUpdate(streamId: String, update: VisitorUpdateDto) {
        sessions[streamId]?.forEach { session ->
            try {
                session.send(Frame.Text(Json.encodeToString(VisitorUpdateDto.serializer(), update)))
            } catch (e: Exception) {
                // Optionally log error per session
            }
        }
    }
}