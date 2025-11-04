package example.com.services.notifications

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
* Registry for managing notification WebSocket sessions.
* Maintains mapping between user IDs and their active WebSocket connections.
*/
object NotificationSessionRegistry {
    // userId -> WebSocketSession
    private val sessions = ConcurrentHashMap<Int, WebSocketSession>()

    /**
    * Registers a user's WebSocket session.
    */
    fun register(userId: Int, session: DefaultWebSocketServerSession) {
        sessions[userId] = session
    }

    /**
    * Unregisters a user's WebSocket session.
    */
    fun unregister(userId: Int) = sessions.remove(userId)

    /**
    * Checks if a user has an active WebSocket session.
    */
    fun hasSession(userId: Int): Boolean = sessions.containsKey(userId)

    /**
    * Sends a JSON payload to a specific user's WebSocket session.
    *
    * @param userId The target user ID
    * @param payloadJson The JSON string to send
    * @return true if sent successfully, false if user is not connected
    */
    suspend fun sendToUser(userId: Int, payloadJson: String): Boolean {
        val session = sessions[userId] ?: return false
        return try {
            session.send(Frame.Text(payloadJson))
            true
        } catch (e: Throwable) {
            sessions.remove(userId)
            false
        }
    }

    /**
    * Gets the set of currently connected user IDs.
    */
    fun getConnectedUsers(): Set<Int> = sessions.keys.toSet()
}
