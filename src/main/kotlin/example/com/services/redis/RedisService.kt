package example.com.services.redis

import example.com.config.AppJson
import example.com.SocialEventType
import example.com.config.awaitFuture
import example.com.routes.dtos.LiveEvent
import example.com.routes.dtos.withDefaults
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisCommandExecutionException
import io.lettuce.core.RedisFuture
import io.lettuce.core.XGroupCreateArgs
import io.lettuce.core.XReadArgs
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Data class representing social events in the Redis stream.
 * Used for follow/unfollow/live started notifications.
 */
@Serializable
data class SocialEvent(
    val type: SocialEventType,             // Event type: "follow" | "unfollow" | "block" | "live_started"
    val actorId: String,                   // User who performed the action
    val actorUsername: String? = null,     // Optional display name for efficiency
    val targetId: String,                  // User who is the target of the action
    val ts: String = Instant.now().toString(), // Event timestamp
    val meta: Map<String, String> = emptyMap() // Additional metadata
)

/**
* Service for Redis operations with clear separation:
* - Pub/Sub for real-time, ephemeral events
* - Streams for durable, persistent event processing
*
* Added explicit methods for Pub/Sub and separate Streams for different workflows
*/
class RedisService(redisUrl: String) {
    val redisClient: RedisClient = RedisClient.create(redisUrl)

    // Separate connections for producers and consumers
    internal val producerConnection: StatefulRedisConnection<String, String> = redisClient.connect()
    internal val consumerConnection: StatefulRedisConnection<String, String> = redisClient.connect()

    private val _producerCommands: RedisAsyncCommands<String, String> = producerConnection.async()
    private val _consumerCommands: RedisAsyncCommands<String, String> = consumerConnection.async()

    // expose for advanced usage (workers)
    val producerCommands: RedisAsyncCommands<String, String> get() = _producerCommands
    val consumerCommands: RedisAsyncCommands<String, String> get() = _consumerCommands

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Use a stable consumer ID
    private val consumerId = "consumer-${System.getenv("HOSTNAME") ?: "default"}"
    private val json: Json = AppJson

    companion object {
        // Separate streams for different durable workflows
        const val MODERATION_STREAM = "moderation_events"
        const val ANALYTICS_STREAM = "analytics_events"
        const val BILLING_STREAM = "billing_events"
        const val SOCIAL_STREAM = "social_events"

        // CHANGE: Pub/Sub channels for real-time events
        const val ROOM_EVENTS_PREFIX = "room_events:"
        const val USER_NOTIFICATIONS_CHANNEL = "user_notifications"

        private const val MAX_HISTORY = 100
    }

    // ==================================================
    // PUB/SUB OPERATIONS (REAL-TIME, EPHEMERAL)
    // ==================================================
    /**
    * Publish real-time event to a room channel
    * Used for instant delivery of chat, likes, joins, etc.
    */
    suspend fun publishToRoom(roomId: String, eventJson: String) {
        producerCommands.publish("${ROOM_EVENTS_PREFIX}$roomId", eventJson).awaitFuture()
    }

    /**
    * Publish user notification
    * Used for cross-instance user notifications
    */
    suspend fun publishToUser(userId: String, eventJson: String) {
        producerCommands.publish(USER_NOTIFICATIONS_CHANNEL, eventJson).awaitFuture()
    }

    // ==================================================
    // STREAM OPERATIONS (DURABLE, PERSISTENT)
    // ==================================================

    /**
    * Add event to moderation stream for durable processing
    * Used for chat moderation, audit logs, suspicious activity
    */
    suspend fun addToModerationStream(event: LiveEvent) {
        println("REDIS: Adding event to moderation stream: ${event::class.simpleName}")

        try {
            val safe = event.withDefaults()
            val eventJson = json.encodeToString(PolymorphicSerializer(LiveEvent::class), safe)
            println("REDIS: Event JSON: $eventJson")

            val result = producerCommands.xadd(MODERATION_STREAM, mapOf("event" to eventJson)).awaitFuture()
            println("REDIS: Successfully added event to stream with ID: $result")
        } catch (e: Exception) {
            println("REDIS: Error adding event to moderation stream: ${e.message}")
            throw e
        }
    }

    /**
    * Add event to analytics stream
    * Used for engagement metrics, viewer behavior, business intelligence
    */
    suspend fun addToAnalyticsStream(event: LiveEvent) {
        val safe = event.withDefaults()
        val eventJson = json.encodeToString(PolymorphicSerializer(LiveEvent::class), safe)
        producerCommands.xadd(ANALYTICS_STREAM, mapOf("event" to eventJson)).awaitFuture()
    }

    /**
    * Add gift event to billing stream
    * Used for financial reconciliation, payout processing, revenue tracking
    */
    suspend fun addToBillingStream(event: LiveEvent.Gift) {
        val eventJson = json.encodeToString(LiveEvent.Gift.serializer(), event)
        producerCommands.xadd(BILLING_STREAM, mapOf("event" to eventJson)).awaitFuture()
    }

    /**
    * Push a simple social event to SOCIAL_STREAM.
    * Social events need durability for notification processing
    */
    suspend fun addSocialEvent(
        type: SocialEventType,
        actorId: Int,
        targetId: Int,
        actorUsername: String? = null,
        meta: Map<String, String> = emptyMap()
    ) {
        val event = SocialEvent(
            type = type,
            actorId = actorId.toString(),
            actorUsername = actorUsername,
            targetId = targetId.toString(),
            ts = Instant.now().toString(),
            meta = meta
        )

        val payload = json.encodeToString(SocialEvent.serializer(), event)
        val map = mutableMapOf<String, String>(
            "event" to payload,
            "type" to event.type.name,
            "actorId" to event.actorId,
            "targetId" to event.targetId,
            "ts" to event.ts
        )
        event.actorUsername?.let { map["actorUsername"] = it }

        producerCommands.xadd(SOCIAL_STREAM, map).awaitFuture()
    }

    /**
    * Creates a consumer group for a stream if it doesn't exist.
    */
    suspend fun createConsumerGroupIfNotExists(streamKey: String, group: String) {
        println("REDISSERVICE: inside createConsumerGroupIfNotExists")
        try {
            println("REDISSERVICE: inside createConsumerGroupIfNotExists try")
            consumerCommands.xgroupCreate(
                XReadArgs.StreamOffset.from(streamKey, "0-0"),
                group,
                XGroupCreateArgs.Builder.mkstream(true)
            ).awaitFuture()
        } catch (e: RedisCommandExecutionException) {
            println("REDISSERVICE: inside createConsumerGroupIfNotExists catch")
            if (!e.message.orEmpty().contains("BUSYGROUP")) throw e
        }
    }

    /**
    * Updated to trigger both Pub/Sub and Stream events
    * Real-time: Immediate notification to followers
    * Durable: Persistent event for offline followers
    */
    suspend fun triggerLiveNotification(userId: Int, username: String, streamId: String? = null) {
        val event = SocialEvent(
            type = SocialEventType.LIVE_STARTED,
            actorId = userId.toString(),
            actorUsername = username,
            targetId = userId.toString(),
            ts = Instant.now().toString(),
            meta = mapOf(
                "streamId" to (streamId ?: ""),
                "timestamp" to Instant.now().toString()
            )
        )

        val payload = json.encodeToString(SocialEvent.serializer(), event)
        val map = mutableMapOf<String, String>(
            "event" to payload,
            "type" to event.type.name,
            "actorId" to event.actorId,
            "targetId" to event.targetId,
            "ts" to event.ts
        )
        event.actorUsername?.let { map["actorUsername"] = it }

        // Durable: Add to stream for notification processing
        producerCommands.xadd(SOCIAL_STREAM, map).awaitFuture()

        // Real-time: Could also publish via Pub/Sub for instant delivery to online users
        // producerCommands.publish("user_live_notifications", payload).awaitFuture()

        println("DEBUG: Triggered live notification for user $userId ($username)")
    }

    // ==================================================
    // ROOM HISTORY OPERATIONS (LIMITED RETENTION)
    // ==================================================

    /**
    * Adds chat messages to room history with size limit.
    * Only used for chat replay, not real-time delivery
    */
    suspend fun addToHistory(roomId: String, event: LiveEvent) {
        if (event is LiveEvent.ChatMessage || event is LiveEvent.SystemMessage) {
            val key = "room:$roomId:history"
            val safe = event.withDefaults()
            val jsonStr = json.encodeToString(PolymorphicSerializer(LiveEvent::class), safe)

            producerCommands.lpush(key, jsonStr).awaitFuture()
            producerCommands.ltrim(key, 0, (MAX_HISTORY - 1).toLong()).awaitFuture()
        }
    }

    /**
    * Retrieves chat history for a room.
    */
    suspend fun getRoomHistory(roomId: String): List<LiveEvent> {
        val key = "room:$roomId:history"
        val jsonList = producerCommands.lrange(key, 0, -1).awaitFuture()
        return jsonList.reversed().mapNotNull { jsonStr ->
            try {
                json.decodeFromString<LiveEvent>(jsonStr)
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
    * Deletes room history (cleanup when room ends).
    */
    suspend fun deleteHistory(roomId: String) {
        producerCommands.del("room:$roomId:history").awaitFuture()
    }

    // ==================================================
    // COUNTER OPERATIONS
    // ==================================================

    /**
    * Increments a counter value in Redis.
    */
    suspend fun incrementCounter(key: String, value: Long): Long =
        producerCommands.incrby(key, value).awaitFuture()

    /**
    * Increments user-specific like count for a room.
    */
    suspend fun incrementUserLikeCount(roomId: String, userId: String, count: Long): Long {
        val key = "room:$roomId:user_likes:$userId"
        return incrementCounter(key, count)
    }

    /**
    * Gets counter value from Redis.
    */
    suspend fun getCounter(key: String): Long? =
        producerCommands.get(key).awaitFuture()?.toLongOrNull()

    /**
    * Cleans up all Redis data for a room when it ends.
    */
    suspend fun deleteCounters(roomId: String) {
        producerCommands.del("room:$roomId:likes").awaitFuture()
        val userKeys = producerCommands.keys("room:$roomId:user_likes:*").awaitFuture()
        if (userKeys.isNotEmpty()) {
            producerCommands.del(*userKeys.toTypedArray()).awaitFuture()
        }
        deleteHistory(roomId)
    }

    // ==================================================
    // ROOM USER OPERATIONS
    // ==================================================

    /**
    * Gets room user count efficiently
    */
    suspend fun getRoomUserCount(roomId: String): Long =
        producerCommands.scard("room:$roomId:users").awaitFuture()

    /**
    * Checks if user is in room
    */
    suspend fun isUserInRoom(roomId: String, userId: String): Boolean =
        producerCommands.sismember("room:$roomId:users", userId).awaitFuture()

    /**
    * Gets all users in room
    */
    suspend fun getRoomUsers(roomId: String): Set<String> =
        producerCommands.smembers("room:$roomId:users").awaitFuture() ?: emptySet()

    // ==================================================
    // HASH OPERATIONS
    // ==================================================

    /**
    * Sets hash field
    */
    suspend fun hset(key: String, field: String, value: String) {
        producerCommands.hset(key, field, value).awaitFuture()
    }

    /**
    * Gets hash field
    */
    suspend fun hget(key: String, field: String): String? =
        producerCommands.hget(key, field).awaitFuture()

    /**
    * Gets all hash fields
    */
    suspend fun hgetall(key: String): Map<String, String> =
        producerCommands.hgetall(key).awaitFuture()

    /**
    * Convenience helper to HSET multiple fields (map). Uses individual hset calls
    * to keep compatibility with Lettuce async API so we can await each write.
    */
    suspend fun hsetAll(key: String, map: Map<String, String>) {
        if (map.isEmpty()) return
        // iterate and await each field set (could be optimized as a single HMSET if available)
        for ((field, value) in map) {
            producerCommands.hset(key, field, value).awaitFuture()
        }
    }

    // ==================================================
    // SET OPERATIONS
    // ==================================================

    /**
    * Adds to set
    */
    suspend fun sadd(key: String, vararg members: String): Long =
        producerCommands.sadd(key, *members).awaitFuture()

    /**
    * Removes from set
    */
    suspend fun srem(key: String, vararg members: String): Long =
        producerCommands.srem(key, *members).awaitFuture()

    /**
    * Generic SMEMBERS wrapper that awaits the RedisFuture and returns a Set<String>.
    */
    suspend fun smembers(key: String): Set<String> =
        producerCommands.smembers(key).awaitFuture() ?: emptySet()

    /**
    * General SISMEMBER wrapper
    */
    suspend fun sismember(key: String, member: String): Boolean =
        producerCommands.sismember(key, member).awaitFuture()

    // ==================================================
    // KEY-VALUE OPERATIONS
    // ==================================================
    /**
     * Simple SET without expiry.
     */
    suspend fun set(key: String, value: String) {
        producerCommands.set(key, value).awaitFuture()
    }

    /**
    * Sets key with expiry
    */
    suspend fun setex(key: String, seconds: Long, value: String) {
        producerCommands.setex(key, seconds, value).awaitFuture()
    }

    /**
    * Sets key if not exists
    */
    suspend fun setnx(key: String, value: String): Boolean =
        producerCommands.setnx(key, value).awaitFuture()

    /**
    * Deletes keys
    */
    suspend fun del(vararg keys: String): Long =
        producerCommands.del(*keys).awaitFuture()

    /**
    * Gets keys by pattern
    */
    suspend fun keys(pattern: String): List<String> =
        producerCommands.keys(pattern).awaitFuture()

    /**
    * Sets expiry
    */
    suspend fun expire(key: String, seconds: Long): Boolean =
        producerCommands.expire(key, seconds).awaitFuture()

    /**
    * Get a string value for a key.
    */
    suspend fun get(key: String): String? =
        producerCommands.get(key).awaitFuture()

    // ==================================================
    // STREAM METADATA OPERATIONS
    // ==================================================

    /**
    * Gets stream metadata from Redis hash.
    */
    suspend fun getStreamMetadata(roomId: String): Map<String, String> =
        producerCommands.hgetall("stream:$roomId").awaitFuture()

    /**
    * Gets stream moderation state.
    */
    suspend fun getStreamModerationState(streamId: String): String? {
        return try {
            val metadata = getStreamMetadata(streamId)
            metadata["state"]
        } catch (e: Exception) {
            null
        }
    }

    // ==================================================
    // UTILITY AND HELPER FUNCTIONS
    // ==================================================

    /**
    * Ping Redis to check connectivity
    */
    suspend fun ping(): Boolean {
        return try {
            val result = producerCommands.ping().awaitFuture()
            result == "PONG"
        } catch (e: Exception) {
            false
        }
    }

    /**
    * Exists wrapper: returns number of keys existing (0 or 1 typically)
    */
    suspend fun exists(key: String): Long =
        producerCommands.exists(key).awaitFuture()

    /**
    * Closes Redis connections.
    */
    fun close() {
        try { serviceScope.cancel() } catch (_: Throwable) {}
        try { producerConnection.close() } catch (_: Throwable) {}
        try { consumerConnection.close() } catch (_: Throwable) {}
        try { redisClient.shutdown() } catch (_: Throwable) {}
    }
}
