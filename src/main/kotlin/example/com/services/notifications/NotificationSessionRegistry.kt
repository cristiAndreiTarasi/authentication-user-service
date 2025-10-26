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

    /**
     * Attempts to send a payload to the user's websocket session.
     * Returns true if the session existed and send succeeded, false otherwise.
     */
    fun sendToUser(userId: Int, payloadJson: String): Boolean {
        val session = sessions[userId] ?: return false
        return try {
            // send is suspend; use runBlocking for callers that are not suspending
            runBlocking {
                session.send(Frame.Text(payloadJson))
            }
            true
        } catch (e: Throwable) {
            // If send fails, remove session and return false so worker can persist.
            sessions.remove(userId)
            false
        }
    }
}
