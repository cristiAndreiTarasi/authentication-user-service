package example.com.services.redis

import redis.clients.jedis.JedisPooled

object RedisManager {
    val jedis = JedisPooled("redis", 6379) // use IP/hostname of your Redis container

    fun set(key: String, value: String, ttlSeconds: Int? = null) {
        if (ttlSeconds != null) {
            jedis.setex(key, ttlSeconds.toLong(), value)
        } else {
            jedis.set(key, value)
        }
    }

    fun get(key: String): String? = jedis.get(key)

    fun del(key: String) = jedis.del(key)
}