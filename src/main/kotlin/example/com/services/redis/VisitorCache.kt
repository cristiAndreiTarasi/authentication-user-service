package example.com.services.redis

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import example.com.services.socket.VisitorDto
import example.com.services.socket.VisitorUpdateDto
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

class VisitorCache {

    private val ttlSeconds = 60 * 60 // 1 hour, or adjust as needed
    private val gson = Gson()

    private fun getKey(streamId: String) = "visitors:$streamId"

    fun incrementVisitorCount(streamId: String, visitor: VisitorDto): VisitorUpdateDto {
        val key = getKey(streamId)
        val current = getVisitorList(key).toMutableList()

        // Avoid duplicates
        if (current.none { it.userId == visitor.userId }) {
            current.add(visitor)
        }

        saveVisitorList(key, current)
        return VisitorUpdateDto(visitorCount = current.size, visitorList = current)
    }

    fun decrementVisitorCount(streamId: String, visitor: VisitorDto): VisitorUpdateDto {
        val key = getKey(streamId)
        val current = getVisitorList(key).filterNot { it.userId == visitor.userId }

        saveVisitorList(key, current)
        return VisitorUpdateDto(visitorCount = current.size, visitorList = current)
    }

    private fun getVisitorList(key: String): List<VisitorDto> {
        val json = RedisManager.jedis.get(key)
        return if (json != null) {
            gson.fromJson(json, object : TypeToken<List<VisitorDto>>() {}.type)
        } else {
            emptyList()
        }
    }

    private fun saveVisitorList(key: String, list: List<VisitorDto>) {
        val json = gson.toJson(list)
        RedisManager.jedis.setex(key, ttlSeconds.toLong(), json)
    }
}

