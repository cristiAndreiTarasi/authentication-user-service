package example.com.services.ws_session

import example.com.services.redis.RedisService
import java.time.Duration

/**
* Distributed session manager that uses Redis for cross-instance session tracking
* Replaces the in-memory SessionManager for room and user session tracking
*/
class DistributedSessionManager(
    private val redisService: RedisService,
    private val instanceId: String
) {
    companion object {
        private val SESSION_TTL = Duration.ofHours(1)
        private val ROOM_TTL = Duration.ofHours(2)
    }

    /**
     * Adds a session to distributed registry
     * Stores: user→session mapping, room→users mapping, session details
     */
    suspend fun addSession(roomId: String, userId: String, username: String) {
        val sessionKey = "session:$userId:$roomId"
        val sessionData = mapOf(
            "userId" to userId,
            "username" to username,
            "roomId" to roomId,
            "instanceId" to instanceId,
            "connectedAt" to System.currentTimeMillis().toString()
        )

        // Store session data (hash) and set TTL
        redisService.hsetAll(sessionKey, sessionData)
        redisService.expire(sessionKey, SESSION_TTL.seconds)

        // Add user to room set and set room TTL
        redisService.sadd("room:$roomId:users", userId)
        redisService.expire("room:$roomId:users", ROOM_TTL.seconds)

        // Track instance→user mapping for cleanup
        redisService.sadd("instance:$instanceId:users", "$userId:$roomId")
    }

    /**
     * Removes session from distributed registry
     * Called when WebSocket disconnects
     */
    suspend fun removeSession(userId: String, roomId: String) {
        val sessionKey = "session:$userId:$roomId"

        // Remove user from room
        redisService.srem("room:$roomId:users", userId)

        // Remove session data
        redisService.del(sessionKey)

        // Remove from instance tracking
        redisService.srem("instance:$instanceId:users", "$userId:$roomId")

        // If room is empty, clean up room data
        val roomUsers = redisService.getRoomUsers(roomId)
        if (roomUsers.isEmpty()) {
            redisService.del("room:$roomId:users")
        }
    }

    /**
     * Gets all users in a room across all instances
     */
    suspend fun getRoomUsers(roomId: String): Set<String> {
        return redisService.getRoomUsers(roomId)
    }

    /**
     * Gets session info for a specific user
     */
    suspend fun getSessionInfo(userId: String, roomId: String): Map<String, String>? {
        val sessionKey = "session:$userId:$roomId"
        return redisService.hgetall(sessionKey)
    }

    /**
     * Checks if a user is in a room (any instance)
     */
    suspend fun isUserInRoom(userId: String, roomId: String): Boolean {
        return redisService.isUserInRoom(roomId, userId)
    }

    /**
     * Gets all sessions for this instance (for cleanup)
     */
    private suspend fun getInstanceSessions(): Set<String> {
        return redisService.smembers("instance:$instanceId:users")
    }

    /**
     * Clean up dead sessions (called on instance startup)
     */
    suspend fun cleanupInstanceSessions() {
        val instanceSessions = getInstanceSessions()
        instanceSessions.forEach { session ->
            val (userId, roomId) = session.split(":")
            removeSession(userId, roomId)
        }
    }
}