package example.com.services.ws_session

import example.com.services.redis.RedisService
import java.time.Duration

/**
 * Distributed permission manager for stream moderation across instances
 * Replaces in-memory PermissionManager for consistent state
 */
class DistributedPermissionManager(
    private val redisService: RedisService
) {
    companion object {
        private val PERMISSION_TTL = Duration.ofHours(3)
    }

    /**
     * Sets stream owner with atomic operation to prevent race conditions
     */
    suspend fun setStreamOwner(
        roomId: String,
        userId: String,
        username: String,
        avatarUrl: String?
    ) {
        val ownerKey = "room:$roomId:owner"
        val ownerInfoKey = "room:$roomId:ownerInfo"

        // Use SETNX for atomic ownership
        val success = redisService.setnx(ownerKey, userId)

        if (success) {
            // We won the ownership race - store owner info fields
            // Use individual hset calls (or hsetAll if you added it)
            redisService.hset(ownerInfoKey, "username", username)
            redisService.hset(ownerInfoKey, "avatarUrl", avatarUrl ?: "")
            redisService.hset(ownerInfoKey, "setAt", System.currentTimeMillis().toString())

            // Set TTLs
            redisService.expire(ownerKey, PERMISSION_TTL.seconds)
            redisService.expire(ownerInfoKey, PERMISSION_TTL.seconds)

            println("DEBUG: User $userId set as stream owner for room $roomId")
        } else {
            println("DEBUG: Room $roomId already has an owner")
        }
    }

    /**
     * Gets stream owner for a room
     */
    suspend fun getStreamOwner(roomId: String): String? {
        return redisService.get("room:$roomId:owner")
    }

    /**
     * Gets stream owner info
     */
    suspend fun getStreamOwnerInfo(roomId: String): Map<String, String> {
        return try {
            redisService.hgetall("room:$roomId:ownerInfo")
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * Checks if user is stream owner
     */
    suspend fun isStreamOwner(roomId: String, userId: String): Boolean {
        val owner = getStreamOwner(roomId)
        return owner == userId
    }

    /**
     * Manages moderator set with atomic operations
     */
    suspend fun grantModerator(roomId: String, userId: String) {
        redisService.sadd("room:$roomId:moderators", userId)
        redisService.expire("room:$roomId:moderators", PERMISSION_TTL.seconds)
    }

    suspend fun revokeModerator(roomId: String, userId: String) {
        redisService.srem("room:$roomId:moderators", userId)
    }

    suspend fun isModerator(roomId: String, userId: String): Boolean {
        return redisService.sismember("room:$roomId:moderators", userId)
    }

    /**
     * Manages muted users
     */
    suspend fun muteUser(roomId: String, userId: String) {
        redisService.sadd("room:$roomId:muted", userId)
        redisService.expire("room:$roomId:muted", PERMISSION_TTL.seconds)
    }

    suspend fun unmuteUser(roomId: String, userId: String) {
        redisService.srem("room:$roomId:muted", userId)
    }

    suspend fun isMuted(roomId: String, userId: String): Boolean {
        return redisService.sismember("room:$roomId:muted", userId)
    }

    /**
     * Manages kicked users
     */
    suspend fun kickUser(roomId: String, userId: String) {
        redisService.sadd("room:$roomId:kicked", userId)
        redisService.expire("room:$roomId:kicked", PERMISSION_TTL.seconds)
    }

    suspend fun isKicked(roomId: String, userId: String): Boolean {
        return redisService.sismember("room:$roomId:kicked", userId)
    }

    /**
     * Checks if room has stream owner
     */
    suspend fun hasStreamOwner(roomId: String): Boolean {
        return redisService.exists("room:$roomId:owner") > 0L
    }

    /**
     * Removes all room data (when stream ends)
     */
    suspend fun removeRoom(roomId: String) {
        val keys = listOf(
            "room:$roomId:owner",
            "room:$roomId:ownerInfo",
            "room:$roomId:moderators",
            "room:$roomId:muted",
            "room:$roomId:kicked"
        )
        redisService.del(*keys.toTypedArray())
    }
}