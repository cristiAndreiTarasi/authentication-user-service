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

        // Use SET with NX (set if not exists) for atomic ownership
        val success = redisService.producerCommands.setnx(ownerKey, userId)

        if (success) {
            // We won the ownership race
            val ownerInfo = mapOf(
                "username" to username,
                "avatarUrl" to (avatarUrl ?: ""),
                "setAt" to System.currentTimeMillis().toString()
            )
            redisService.producerCommands.hset(ownerInfoKey, ownerInfo)

            // Set TTLs
            redisService.producerCommands.expire(ownerKey, PERMISSION_TTL)
            redisService.producerCommands.expire(ownerInfoKey, PERMISSION_TTL)

            println("DEBUG: User $userId set as stream owner for room $roomId")
        } else {
            println("DEBUG: Room $roomId already has an owner")
        }
    }

    /**
    * Gets stream owner for a room
    */
    suspend fun getStreamOwner(roomId: String): String? {
        return redisService.producerCommands.get("room:$roomId:owner")
    }

    /**
    * Gets stream owner info
    */
    suspend fun getStreamOwnerInfo(roomId: String): Map<String, String>? {
        return redisService.producerCommands.hgetall("room:$roomId:ownerInfo")
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
        redisService.producerCommands.sadd("room:$roomId:moderators", userId)
        redisService.producerCommands.expire("room:$roomId:moderators", PERMISSION_TTL)
    }

    suspend fun revokeModerator(roomId: String, userId: String) {
        redisService.producerCommands.srem("room:$roomId:moderators", userId)
    }

    suspend fun isModerator(roomId: String, userId: String): Boolean {
        return redisService.producerCommands.sismember("room:$roomId:moderators", userId)
    }

    /**
    * Manages muted users
    */
    suspend fun muteUser(roomId: String, userId: String) {
        redisService.producerCommands.sadd("room:$roomId:muted", userId)
        redisService.producerCommands.expire("room:$roomId:muted", PERMISSION_TTL)
    }

    suspend fun unmuteUser(roomId: String, userId: String) {
        redisService.producerCommands.srem("room:$roomId:muted", userId)
    }

    suspend fun isMuted(roomId: String, userId: String): Boolean {
        return redisService.producerCommands.sismember("room:$roomId:muted", userId)
    }

    /**
    * Manages kicked users
    */
    suspend fun kickUser(roomId: String, userId: String) {
        redisService.producerCommands.sadd("room:$roomId:kicked", userId)
        redisService.producerCommands.expire("room:$roomId:kicked", PERMISSION_TTL)
    }

    suspend fun isKicked(roomId: String, userId: String): Boolean {
        return redisService.producerCommands.sismember("room:$roomId:kicked", userId)
    }

    /**
    * Checks if room has stream owner
    */
    suspend fun hasStreamOwner(roomId: String): Boolean {
        return redisService.producerCommands.exists("room:$roomId:owner") > 0
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
        redisService.producerCommands.del(*keys.toTypedArray())
    }
}