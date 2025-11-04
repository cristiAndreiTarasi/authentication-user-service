package example.com.services.ws_session

import io.ktor.websocket.WebSocketSession
import org.litote.kmongo.MongoOperator
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
* Manages WebSocket sessions for live rooms.
* Tracks which users are in which rooms and their session information.
*/
object SessionManager {
    // roomId -> Set of WebSocketSession
    private val roomSessions = ConcurrentHashMap<String, MutableSet<WebSocketSession>>()
    // WebSocketSession -> SessionInfo
    private val sessionToInfo = ConcurrentHashMap<WebSocketSession, SessionInfo>()
    private val lock = Any()

    /**
    * Data class storing user session information.
    */
    data class SessionInfo(
        val userId: String,
        val username: String,
        val roomId: String
    )

    /**
    * Adds a session to the manager.
    */
    fun addSession(roomId: String, userId: String, username: String, session: WebSocketSession) {
        synchronized(lock) {
            roomSessions.computeIfAbsent(roomId) { ConcurrentHashMap.newKeySet() }.add(session)
            sessionToInfo[session] = SessionInfo(userId, username, roomId)
        }
    }

    /**
    * Removes a session from the manager.
    */
    fun removeSession(session: WebSocketSession) {
        synchronized(lock) {
            val info = sessionToInfo[session] ?: return
            roomSessions[info.roomId]?.remove(session)
            sessionToInfo.remove(session)
        }
    }

    /**
    * Gets all sessions in a room.
    */
    fun getRoomSessions(roomId: String): Set<WebSocketSession> {
        return roomSessions[roomId]?.toSet() ?: emptySet()
    }

    /**
    * Gets session info for a specific user in a room.
    */
    fun getSessionInfo(roomId: String, userId: String): SessionInfo? {
        return sessionToInfo.entries.firstOrNull {
            it.value.roomId == roomId && it.value.userId == userId
        }?.value
    }

    /**
    * Gets username for a session.
    */
    fun getUsername(session: WebSocketSession): String? {
        return sessionToInfo[session]?.username
    }

    /**
    * Gets user ID for a session.
    */
    fun getUserId(session: WebSocketSession): String? {
        return sessionToInfo[session]?.userId
    }

    /**
    * Gets a specific session by room and user ID.
    */
    fun getSession(roomId: String, userId: String): WebSocketSession? {
        return sessionToInfo.entries.firstOrNull {
            it.value.roomId == roomId && it.value.userId == userId
        }?.key
    }
}

