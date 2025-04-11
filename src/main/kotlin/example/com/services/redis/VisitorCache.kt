package example.com.services.redis

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import example.com.routes.dtos.VisitorDto
import example.com.routes.dtos.VisitorUpdateDto

class VisitorCache {
    private val ttlSeconds = 3600
    private val gson = Gson()

    private fun getKey(streamId: String) = "visitors:$streamId"

    fun logVisitorList(streamId: String) {
        val key = "visitors:$streamId"
        val contents: String? = RedisManager.get(key)
        println("Redis contents for key '$key': $contents")
    }


    fun incrementVisitorCount(streamId: String, visitor: VisitorDto): VisitorUpdateDto {
        val key = getKey(streamId)
        val current = getVisitorList(key).toMutableList()
        // Avoid duplicates
        if (current.none { it.userId == visitor.userId }) {
            current.add(visitor)
        }
        saveVisitorList(key, current)
        val update = VisitorUpdateDto(visitorCount = current.size, visitorList = current)
        println("After increment, visitor list updated to: $update")
        // Optionally log the raw JSON from Redis:
        logVisitorList(streamId)
        return update
    }

    fun decrementVisitorCount(streamId: String, visitor: VisitorDto): VisitorUpdateDto {
        val key = getKey(streamId)
        val current = getVisitorList(key).filterNot { it.userId == visitor.userId }
        saveVisitorList(key, current)
        return VisitorUpdateDto(visitorCount = current.size, visitorList = current)
    }

    fun getCurrentState(streamId: String): VisitorUpdateDto {
        val key = getKey(streamId)
        val list = getVisitorList(key)
        return VisitorUpdateDto(visitorCount = list.size, visitorList = list)
    }

    fun clearStream(streamId: String) {
        RedisManager.del(getKey(streamId))
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

