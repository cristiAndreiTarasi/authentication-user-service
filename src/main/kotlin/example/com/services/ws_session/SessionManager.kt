package example.com.services.ws_session

import io.ktor.websocket.WebSocketSession
import org.litote.kmongo.MongoOperator
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

object SessionManager {
    private val roomSessions = ConcurrentHashMap<String, MutableSet<WebSocketSession>>()
    private val sessionToInfo = ConcurrentHashMap<WebSocketSession, SessionInfo>()
    private val lock = Any()

    data class SessionInfo(
        val userId: String,
        val username: String,
        val roomId: String
    )

    fun addSession(roomId: String, userId: String, username: String, session: WebSocketSession) {
        synchronized(lock) {
            roomSessions.computeIfAbsent(roomId) { ConcurrentHashMap.newKeySet() }.add(session)
            sessionToInfo[session] = SessionInfo(userId, username, roomId)
        }
    }

    fun removeSession(session: WebSocketSession) {
        synchronized(lock) {
            val info = sessionToInfo[session] ?: return
            roomSessions[info.roomId]?.remove(session)
            sessionToInfo.remove(session)
        }
    }

    fun getRoomSessions(roomId: String): Set<WebSocketSession> {
        return roomSessions[roomId]?.toSet() ?: emptySet()
    }

    fun getSessionInfo(roomId: String, userId: String): SessionInfo? {
        return sessionToInfo.entries.firstOrNull {
            it.value.roomId == roomId && it.value.userId == userId
        }?.value
    }

    fun getUsername(session: WebSocketSession): String? {
        return sessionToInfo[session]?.username
    }

    fun getUserId(session: WebSocketSession): String? {
        return sessionToInfo[session]?.userId
    }

    fun getSession(roomId: String, userId: String): WebSocketSession? {
        return sessionToInfo.entries.firstOrNull {
            it.value.roomId == roomId && it.value.userId == userId
        }?.key
    }
}

