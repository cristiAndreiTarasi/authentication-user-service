package example.com.services.ws_session

import io.ktor.websocket.WebSocketSession
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

object SessionManager {
    private val roomSessions = ConcurrentHashMap<String, MutableSet<WebSocketSession>>()
    private val sessionToUser = ConcurrentHashMap<WebSocketSession, Pair<String, String>>()
    private val sessionToRoom = ConcurrentHashMap<WebSocketSession, String>()
    private val lock = Any()

    fun addSession(roomId: String, userId: String, username: String, session: WebSocketSession) {
        synchronized(lock) {
            roomSessions.computeIfAbsent(roomId) { CopyOnWriteArraySet() }.add(session)
            sessionToUser[session] = userId to username
            sessionToRoom[session] = roomId
        }
    }

    fun removeSession(session: WebSocketSession) {
        synchronized(lock) {
            val roomId = sessionToRoom[session]
            if (roomId != null) {
                roomSessions[roomId]?.remove(session)
                if (roomSessions[roomId]?.isEmpty() == true) {
                    roomSessions.remove(roomId)
                }
            }
            sessionToUser.remove(session)
            sessionToRoom.remove(session)
        }
    }

    fun getRoomSessions(roomId: String): Set<WebSocketSession> {
        return roomSessions[roomId]?.toSet() ?: emptySet()
    }

    fun getUsername(session: WebSocketSession): String? {
        return sessionToUser[session]?.second
    }

    fun getUserId(session: WebSocketSession): String? {
        return sessionToUser[session]?.first
    }

    fun getSession(roomId: String, userId: String): WebSocketSession? {
        return roomSessions[roomId]?.firstOrNull {
            sessionToUser[it]?.first == userId
        }
    }
}

