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
// RedisService.kt
class RedisService(redisUrl: String) {
    val redisClient: RedisClient = RedisClient.create(redisUrl)

    // Separate connections for producers and consumers
    internal val producerConnection: StatefulRedisConnection<String, String> = redisClient.connect()
    internal val consumerConnection: StatefulRedisConnection<String, String> = redisClient.connect()

    private val _producerCommands: RedisAsyncCommands<String, String> = producerConnection.async()
    private val _consumerCommands: RedisAsyncCommands<String, String> = consumerConnection.async()

    val producerCommands: RedisAsyncCommands<String, String> get() = _producerCommands
    val consumerCommands: RedisAsyncCommands<String, String> get() = _consumerCommands

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val consumerId = "consumer-${System.getenv("HOSTNAME") ?: "default"}"
    private val json: Json = AppJson

    companion object {
        private const val MAX_HISTORY = 100
        private const val COUNTER_TTL_HOURS = 24L
        private const val SESSION_TTL_HOURS = 2L
    }

    // ==================================================
    // STREAM OPERATIONS (DURABLE, PERSISTENT)
    // ==================================================

    /**
     * Routes LiveEvent to appropriate stream based on event category
     * Replaces the old addToStream method
     */
    suspend fun addToCategorizedStream(event: LiveEvent) {
        val safeEvent = event.withDefaults()
        val eventJson = json.encodeToString(PolymorphicSerializer(LiveEvent::class), safeEvent)

        when (event.getEventCategory()) {
            EventCategory.CHAT -> addToChatStream(event, eventJson)
            EventCategory.MODERATION -> addToModerationStream(event, eventJson)
            EventCategory.ANALYTICS -> addToAnalyticsStream(event, eventJson)
            EventCategory.BILLING -> addToBillingStream(event, eventJson)
            EventCategory.SOCIAL -> addToSocialStream(event, eventJson)
            EventCategory.CONTROL -> addToControlStream(event, eventJson)
            EventCategory.REAL_TIME_ONLY -> {
                // These events only go through Pub/Sub, not streams
                println("DEBUG: Real-time only event ${event::class.simpleName} not persisted to stream")
            }
        }
    }

    /**
     * Add event to chat stream for durable processing
     */
    private suspend fun addToChatStream(event: LiveEvent, eventJson: String) {
        try {
            producerCommands.xadd(RedisStreams.CHAT_STREAM, mapOf("event" to eventJson)).awaitFuture()
            println("DEBUG: Added to chat stream: ${event::class.simpleName}")
        } catch (e: Exception) {
            println("ERROR: Failed to add to chat stream: ${e.message}")
            throw e
        }
    }

    /**
     * Add event to moderation stream for audit trail
     */
    suspend fun addToModerationStream(event: LiveEvent, eventJson: String) {
        try {
            producerCommands.xadd(RedisStreams.MODERATION_STREAM, mapOf("event" to eventJson)).awaitFuture()
            println("DEBUG: Added to moderation stream: ${event::class.simpleName}")
        } catch (e: Exception) {
            println("ERROR: Failed to add to moderation stream: ${e.message}")
            throw e
        }
    }

    /**
     * Add event to analytics stream for business intelligence
     */
    private suspend fun addToAnalyticsStream(event: LiveEvent, eventJson: String) {
        try {
            producerCommands.xadd(RedisStreams.ANALYTICS_STREAM, mapOf("event" to eventJson)).awaitFuture()
            println("DEBUG: Added to analytics stream: ${event::class.simpleName}")
        } catch (e: Exception) {
            println("ERROR: Failed to add to analytics stream: ${e.message}")
            throw e
        }
    }

    /**
     * Add gift event to billing stream for financial reconciliation
     */
    private suspend fun addToBillingStream(event: LiveEvent, eventJson: String) {
        try {
            producerCommands.xadd(RedisStreams.BILLING_STREAM, mapOf("event" to eventJson)).awaitFuture()
            println("DEBUG: Added to billing stream: ${event::class.simpleName}")
        } catch (e: Exception) {
            println("ERROR: Failed to add to billing stream: ${e.message}")
            throw e
        }
    }

    /**
     * Add social event to social stream for notification processing
     */
    private suspend fun addToSocialStream(event: LiveEvent, eventJson: String) {
        try {
            producerCommands.xadd(RedisStreams.SOCIAL_STREAM, mapOf("event" to eventJson)).awaitFuture()
            println("DEBUG: Added to social stream: ${event::class.simpleName}")
        } catch (e: Exception) {
            println("ERROR: Failed to add to social stream: ${e.message}")
            throw e
        }
    }

    /**
     * Add control event to control stream for system coordination
     */
    private suspend fun addToControlStream(event: LiveEvent, eventJson: String) {
        try {
            producerCommands.xadd(RedisStreams.CROSS_INSTANCE_CONTROL, mapOf("event" to eventJson)).awaitFuture()
            println("DEBUG: Added to control stream: ${event::class.simpleName}")
        } catch (e: Exception) {
            println("ERROR: Failed to add to control stream: ${e.message}")
            throw e
        }
    }

    /**
     * Push a social event to SOCIAL_STREAM (for NotificationWorker)
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

        producerCommands.xadd(RedisStreams.SOCIAL_STREAM, map).awaitFuture()
        println("DEBUG: Added social event to stream: $type")
    }

    /**
     * Creates consumer groups for all streams if they don't exist
     */
    suspend fun initializeStreamConsumerGroups() {
        val streams = listOf(
            RedisStreams.CHAT_STREAM,
            RedisStreams.MODERATION_STREAM,
            RedisStreams.ANALYTICS_STREAM,
            RedisStreams.BILLING_STREAM,
            RedisStreams.SOCIAL_STREAM,
            RedisStreams.CROSS_INSTANCE_CONTROL
        )

        streams.forEach { stream ->
            try {
                createConsumerGroupIfNotExists(stream, "${stream}_group")
                println("DEBUG: Initialized consumer group for stream: $stream")
            } catch (e: Exception) {
                println("WARN: Failed to initialize consumer group for $stream: ${e.message}")
            }
        }
    }

    /**
     * Creates a consumer group for a stream if it doesn't exist.
     */
    suspend fun createConsumerGroupIfNotExists(streamKey: String, group: String) {
        try {
            consumerCommands.xgroupCreate(
                XReadArgs.StreamOffset.from(streamKey, "0-0"),
                group,
                XGroupCreateArgs.Builder.mkstream(true)
            ).awaitFuture()
        } catch (e: RedisCommandExecutionException) {
            if (!e.message.orEmpty().contains("BUSYGROUP")) throw e
        }
    }

    /**
     * Trigger live notifications when a user goes live
     * This adds a social event to the SOCIAL_STREAM for the NotificationWorker to process
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

        producerCommands.xadd(RedisStreams.SOCIAL_STREAM, map).awaitFuture()
        println("DEBUG: Triggered live notification for user $userId ($username)")
    }

    // ==================================================
    // PUB/SUB OPERATIONS (REAL-TIME, EPHEMERAL)
    // ==================================================

    /**
     * Publish real-time event to a room channel
     */
    suspend fun publishToRoom(roomId: String, eventJson: String) {
        producerCommands.publish("${RedisStreams.ROOM_EVENTS_PREFIX}$roomId", eventJson).awaitFuture()
    }

    /**
     * Publish user notification
     */
    suspend fun publishToUser(userId: String, eventJson: String) {
        producerCommands.publish(RedisStreams.USER_NOTIFICATIONS_CHANNEL, eventJson).awaitFuture()
    }

    // ==================================================
    // ROOM HISTORY OPERATIONS (LIMITED RETENTION)
    // ==================================================

    /**
     * Adds chat messages to room history with size limit
     */
    suspend fun addToHistory(roomId: String, event: LiveEvent) {
        if (event is LiveEvent.ChatMessage || event is LiveEvent.SystemMessage) {
            val key = "${RedisStreams.ROOM_HISTORY_PREFIX}$roomId"
            val safe = event.withDefaults()
            val jsonStr = json.encodeToString(PolymorphicSerializer(LiveEvent::class), safe)

            producerCommands.lpush(key, jsonStr).awaitFuture()
            producerCommands.ltrim(key, 0, (MAX_HISTORY - 1).toLong()).awaitFuture()
        }
    }

    /**
     * Retrieves chat history for a room
     */
    suspend fun getRoomHistory(roomId: String): List<LiveEvent> {
        val key = "${RedisStreams.ROOM_HISTORY_PREFIX}$roomId"
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
     * Deletes room history (cleanup when room ends)
     */
    suspend fun deleteHistory(roomId: String) {
        producerCommands.del("${RedisStreams.ROOM_HISTORY_PREFIX}$roomId").awaitFuture()
    }

    // ==================================================
    // COUNTER OPERATIONS
    // ==================================================

    /**
     * Increments a counter value in Redis with TTL
     */
    suspend fun incrementCounter(key: String, value: Long): Long {
        val result = producerCommands.incrby(key, value).awaitFuture()
        // Set TTL if this is a new key
        producerCommands.expire(key, COUNTER_TTL_HOURS * 3600).awaitFuture()
        return result
    }

    /**
     * Increments user-specific like count for a room
     */
    suspend fun incrementUserLikeCount(roomId: String, userId: String, count: Long): Long {
        val key = "${RedisStreams.ROOM_COUNTERS_PREFIX}$roomId:user_likes:$userId"
        return incrementCounter(key, count)
    }

    /**
     * Gets counter value from Redis
     */
    suspend fun getCounter(key: String): Long? =
        producerCommands.get(key).awaitFuture()?.toLongOrNull()

    /**
     * Cleans up all Redis data for a room when it ends
     */
    suspend fun deleteCounters(roomId: String) {
        val keysToDelete = mutableListOf<String>()

        // Basic counters
        keysToDelete.add("${RedisStreams.ROOM_COUNTERS_PREFIX}$roomId:likes")

        // User like counters
        val userKeys = producerCommands.keys("${RedisStreams.ROOM_COUNTERS_PREFIX}$roomId:user_likes:*").awaitFuture()
        keysToDelete.addAll(userKeys)

        // Gift counters
        val giftKeys = producerCommands.keys("${RedisStreams.ROOM_COUNTERS_PREFIX}$roomId:gifts:*").awaitFuture()
        keysToDelete.addAll(giftKeys)

        if (keysToDelete.isNotEmpty()) {
            producerCommands.del(*keysToDelete.toTypedArray()).awaitFuture()
        }

        deleteHistory(roomId)
    }

    // ==================================================
    // SESSION MANAGEMENT OPERATIONS
    // ==================================================

    /**
     * Room user operations with TTL
     */
    suspend fun getRoomUserCount(roomId: String): Long =
        producerCommands.scard("room:$roomId:users").awaitFuture()

    suspend fun isUserInRoom(roomId: String, userId: String): Boolean =
        producerCommands.sismember("room:$roomId:users", userId).awaitFuture()

    suspend fun getRoomUsers(roomId: String): Set<String> =
        producerCommands.smembers("room:$roomId:users").awaitFuture() ?: emptySet()

    suspend fun addUserToRoom(roomId: String, userId: String) {
        producerCommands.sadd("room:$roomId:users", userId).awaitFuture()
        producerCommands.expire("room:$roomId:users", SESSION_TTL_HOURS * 3600).awaitFuture()
    }

    suspend fun removeUserFromRoom(roomId: String, userId: String) {
        producerCommands.srem("room:$roomId:users", userId).awaitFuture()
    }

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

    fun close() {
        try { serviceScope.cancel() } catch (_: Throwable) {}
        try { producerConnection.close() } catch (_: Throwable) {}
        try { consumerConnection.close() } catch (_: Throwable) {}
        try { redisClient.shutdown() } catch (_: Throwable) {}
    }
}
