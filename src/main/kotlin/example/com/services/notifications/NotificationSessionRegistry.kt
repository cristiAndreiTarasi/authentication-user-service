package example.com.services.notifications

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

object NotificationSessionRegistry {
    // userId -> WebSocketSession
    private val sessions = ConcurrentHashMap<Int, WebSocketSession>()

    fun register(userId: Int, session: DefaultWebSocketServerSession) {
        sessions[userId] = session
    }
    fun unregister(userId: Int) = sessions.remove(userId)
    fun hasSession(userId: Int): Boolean = sessions.containsKey(userId)

    suspend fun sendToUser(userId: Int, payloadJson: String): Boolean {
        println("DEBUG: sendToUser called for user $userId")
        val session = sessions[userId] ?: return false
        return try {
            session.send(Frame.Text(payloadJson))
            true
        } catch (e: Throwable) {
            sessions.remove(userId)
            false
        }
    }

    fun getConnectedUsers(): Set<Int> = sessions.keys.toSet()
}
