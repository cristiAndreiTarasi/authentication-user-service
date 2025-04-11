package example.com.services.role

import example.com.services.redis.RedisManager

// RoleService.kt
class RoleService {

    // Returns the Redis key for a stream's roles.
    private fun getKey(streamId: String) = "stream:$streamId:roles"

    /**
     * Assign a scoped role to a user for a particular stream.
     * Stores the mapping in a Redis hash.
     */
    fun assignScopedRole(streamId: String, userId: String, role: String) {
        val key = getKey(streamId)
        RedisManager.jedis.hset(key, userId, role)
        // Optionally, set an expiration if desired:
        // RedisManager.jedis.expire(key, 3600)
    }

    /**
     * Retrieve a user's role for the stream.
     */
    fun getScopedRole(streamId: String, userId: String): String? {
        val key = getKey(streamId)
        return RedisManager.jedis.hget(key, userId)
    }

    /**
     * Remove a single user from a stream.
     */
    fun removeUserFromStream(streamId: String, userId: String) {
        val key = getKey(streamId)
        RedisManager.jedis.hdel(key, userId)
    }

    /**
     * Clear all scoped roles for a stream (called when the publisher disconnects).
     */
    fun clearStreamRoles(streamId: String) {
        val key = getKey(streamId)
        RedisManager.jedis.del(key)
    }
}

