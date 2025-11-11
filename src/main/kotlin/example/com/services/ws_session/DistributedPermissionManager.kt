package example.com.services.ws_session

import example.com.services.redis.RedisService
import example.com.services.redis.ShardedRedisService
import java.time.Duration

/**
 * Distributed permission manager for stream moderation across instances
 * Replaces in-memory PermissionManager for consistent state
 */
class DistributedPermissionManager(
    private val shardedRedisService: ShardedRedisService
) {
    companion object {
        private val PERMISSION_TTL = Duration.ofHours(3)
    }

    /**
     * Sets stream owner with atomic operation to prevent race conditions.
     * roomId can be either streamKey (UUID) or numeric streamId string.
     */
    suspend fun setStreamOwner(
        roomId: String,
        userId: String
    ): Boolean {
        val ownerKey = "room:$roomId:owner"

        // Atomic SETNX - returns true only if key didn't exist
        val success = shardedRedisService.setnx(ownerKey, userId)

        if (success) {
            val ownerInfoKey = "room:$roomId:ownerInfo"
            shardedRedisService.hsetAll(ownerInfoKey, mapOf(
                "userId" to userId,
                "setAt" to System.currentTimeMillis().toString()
            ))

            // Use PERMISSION_TTL
            shardedRedisService.expire(ownerKey, PERMISSION_TTL.seconds)
            shardedRedisService.expire(ownerInfoKey, PERMISSION_TTL.seconds)

            return true
        }

        return false
    }

    /**
     * Resolve and return the owner for a roomId.
     * Tries:
     * 1) 'room:<roomId>:owner' (handles streamKey mode)
     * 2) 'streamKey:<roomId>:streamId' -> then 'room:<streamId>:owner' (legacy numeric mode)
     */
    suspend fun getStreamOwner(roomId: String): String? {
        try {
            // 1) Direct lookup (streamKey or numeric if written that way)
            val direct = shardedRedisService.get("room:$roomId:owner")
            if (!direct.isNullOrBlank()) {
                return direct.trim()
            }

            // 2) Fallback: maybe roomId is a streamKey mapping to numeric streamId
            val mapped = shardedRedisService.get("streamKey:$roomId:streamId")
            if (!mapped.isNullOrBlank()) {
                val numericOwner = shardedRedisService.get("room:${mapped.trim()}:owner")
                if (!numericOwner.isNullOrBlank()) {
                    return numericOwner.trim()
                }
            }
        } catch (e: Exception) {
            println("WARN: getStreamOwner error for room=$roomId: ${e.message}")
        }
        return null
    }

    /**
     * Gets stream owner info map (ownerInfo hash) for a roomId (tries both direct and numeric fallback).
     */
    suspend fun getStreamOwnerInfo(roomId: String): Map<String, String> {
        return try {
            val directKey = "room:$roomId:ownerInfo"
            var info = try { shardedRedisService.hgetall(directKey) } catch (_: Exception) { emptyMap<String,String>() }
            if (info.isNotEmpty()) return info

            val mapped = shardedRedisService.get("streamKey:$roomId:streamId")
            if (!mapped.isNullOrBlank()) {
                val numericKey = "room:${mapped.trim()}:ownerInfo"
                info = try { shardedRedisService.hgetall(numericKey) } catch (_: Exception) { emptyMap<String,String>() }
                if (info.isNotEmpty()) return info
            }

            emptyMap()
        } catch (e: Exception) {
            println("WARN: getStreamOwnerInfo error for room=$roomId: ${e.message}")
            emptyMap()
        }
    }

    /**
     * Checks if user is stream owner; uses normalized string comparison
     */
    suspend fun isStreamOwner(roomId: String, userId: String): Boolean {
        val owner = getStreamOwner(roomId) ?: return false
        // normalize both sides
        return owner.trim() == userId.trim()
    }

    // moderators, muted, kicked now include logging so you can see SADD/SREM results in server logs (CHANGED)
    suspend fun grantModerator(roomId: String, userId: String) {
        val added = shardedRedisService.sadd("room:$roomId:moderators", userId)
        shardedRedisService.expire("room:$roomId:moderators", PERMISSION_TTL.seconds)
    }

    suspend fun revokeModerator(roomId: String, userId: String) {
        val removed = shardedRedisService.srem("room:$roomId:moderators", userId)
    }

    suspend fun isModerator(roomId: String, userId: String): Boolean {
        return shardedRedisService.sismember("room:$roomId:moderators", userId)
    }

    suspend fun muteUser(roomId: String, userId: String) {
        val added = shardedRedisService.sadd("room:$roomId:muted", userId)
        shardedRedisService.expire("room:$roomId:muted", PERMISSION_TTL.seconds)
    }

    suspend fun unmuteUser(roomId: String, userId: String) {
        val removed = shardedRedisService.srem("room:$roomId:muted", userId)
    }

    suspend fun isMuted(roomId: String, userId: String): Boolean {
        return shardedRedisService.sismember("room:$roomId:muted", userId)
    }

    suspend fun kickUser(roomId: String, userId: String) {
        val added = shardedRedisService.sadd("room:$roomId:kicked", userId)
        shardedRedisService.expire("room:$roomId:kicked", PERMISSION_TTL.seconds)
    }

    suspend fun isKicked(roomId: String, userId: String): Boolean {
        return shardedRedisService.sismember("room:$roomId:kicked", userId)
    }

    suspend fun hasStreamOwner(roomId: String): Boolean {
        return try {
            (shardedRedisService.exists("room:$roomId:owner") > 0L) ||
                    (!shardedRedisService.get("streamKey:$roomId:streamId").isNullOrBlank())
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Removes all room data (when stream ends). Will attempt to remove both canonical streamKey keys
     * and legacy numeric keys if mapping exists.
     */
    suspend fun removeRoom(roomId: String) {
        try {
            // Remove canonical keys for provided roomId
            val keys = mutableListOf(
                "room:$roomId:owner",
                "room:$roomId:ownerInfo",
                "room:$roomId:moderators",
                "room:$roomId:muted",
                "room:$roomId:kicked"
            )
            // If there's a mapping, remove numeric keys as well and delete mapping
            val mapped = shardedRedisService.get("streamKey:$roomId:streamId")
            if (!mapped.isNullOrBlank()) {
                keys.addAll(listOf(
                    "room:${mapped.trim()}:owner",
                    "room:${mapped.trim()}:ownerInfo",
                    "room:${mapped.trim()}:moderators",
                    "room:${mapped.trim()}:muted",
                    "room:${mapped.trim()}:kicked"
                ))
                keys.add("streamKey:$roomId:streamId")
            }
            shardedRedisService.del(*keys.toTypedArray())
        } catch (e: Exception) {
            println("WARN: removeRoom error for room=$roomId: ${e.message}")
        }
    }
}
