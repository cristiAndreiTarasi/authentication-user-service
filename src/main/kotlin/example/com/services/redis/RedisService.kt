package example.com.services.redis

import example.com.LiveEventJson
import example.com.SocialEventType
import example.com.routes.dtos.LiveEvent
import example.com.routes.dtos.withDefaults
import example.com.services.ws_session.CrossInstanceBroadcaster
import example.com.services.ws_session.SessionManager
import io.ktor.websocket.Frame
import io.lettuce.core.Consumer
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisCommandExecutionException
import io.lettuce.core.RedisException
import io.lettuce.core.StreamMessage
import io.lettuce.core.XGroupCreateArgs
import io.lettuce.core.XReadArgs
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant

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
* Service for Redis operations including event streaming, counters, and history.
* Uses separate connections for producers and consumers to avoid blocking.
*/
class RedisService(redisUrl: String) {
    private val redisClient: RedisClient = RedisClient.create(redisUrl)

    // Separate connections for producers and consumers
    private val producerConnection: StatefulRedisConnection<String, String> = redisClient.connect()
    private val consumerConnection: StatefulRedisConnection<String, String> = redisClient.connect()

    private val _producerCommands: RedisCommands<String, String> = producerConnection.sync()
    private val _consumerCommands: RedisCommands<String, String> = consumerConnection.sync()

    // expose for advanced usage (workers)
    val producerCommands: RedisCommands<String, String> get() = _producerCommands
    val consumerCommands: RedisCommands<String, String> get() = _consumerCommands

    // Use a stable consumer ID (e.g. pod hostname in k8s)
    private val consumerId = "consumer-${System.getenv("HOSTNAME") ?: "default"}"

    // Use a lightweight Json instance for SocialEvent & notification payloads. We dont use the LiveEntJson here
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    companion object {
        const val LIVE_STREAM = "live_events"
        const val SOCIAL_STREAM = "social_events"
    }

    /**
    * Push a simple social event (follow/unfollow) to SOCIAL_STREAM.
    * Message includes both an 'event' JSON field and explicit typed fields to make consumer filtering cheap.
    */
    fun addSocialEvent(
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

        // Serialize the full payload
        val payload = json.encodeToString(SocialEvent.serializer(), event)

        // Build a Map<String, String> for xadd - omit nullable fields if null
        val map = mutableMapOf<String, String>(
            "event" to payload,
            "type" to event.type.name,
            "actorId" to event.actorId,
            "targetId" to event.targetId,
            "ts" to event.ts
        )
        event.actorUsername?.let { map["actorUsername"] = it }

        producerCommands.xadd(SOCIAL_STREAM, map)
    }

    /**
     * Creates a consumer group for a stream if it doesn't exist.
     */
    fun createConsumerGroupIfNotExists(streamKey: String, group: String) {
        try {
            consumerCommands.xgroupCreate(
                XReadArgs.StreamOffset.from(streamKey, "0-0"),
                group,
                XGroupCreateArgs.Builder.mkstream(true)
            )
        } catch (e: RedisCommandExecutionException) {
            // Ignore BUSYGROUP - group already exists
            if (!e.message.orEmpty().contains("BUSYGROUP")) throw e
        }
    }

    /**
    * Adds a live event to the Redis stream for broadcasting.
    */
    fun addToStream(event: LiveEvent) {
        val safe = event.withDefaults()
        val json = LiveEventJson.encodeToString(PolymorphicSerializer(LiveEvent::class), safe)
        producerCommands.xadd("live_events", mapOf("event" to json))
    }

    /**
    * Triggers live notifications when a user goes live.
    * Adds event to social stream for processing by NotificationWorker.
    */
    fun triggerLiveNotification(userId: Int, username: String, streamId: String? = null) {
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

        producerCommands.xadd(SOCIAL_STREAM, map)
        println("DEBUG: Triggered live notification for user $userId ($username)")
    }

    /**
    * Adds chat messages to room history with size limit.
    */
    private val MAX_HISTORY = 100
    fun addToHistory(roomId: String, event: LiveEvent) {
        if (event is LiveEvent.ChatMessage || event is LiveEvent.SystemMessage) {
            val key = "room:$roomId:history"
            val safe = event.withDefaults()
            val jsonStr = LiveEventJson.encodeToString(PolymorphicSerializer(LiveEvent::class), safe)
            producerCommands.xadd(LIVE_STREAM, mapOf("event" to jsonStr))
            producerCommands.lpush(key, jsonStr)
            producerCommands.ltrim(key, 0, (MAX_HISTORY - 1).toLong()) // Keep only recent messages
        }
    }

    /**
    * Retrieves chat history for a room.
    */
    suspend fun getRoomHistory(roomId: String): List<LiveEvent> = withContext(Dispatchers.IO) {
        val key = "room:$roomId:history"
        val jsonList = producerCommands.lrange(key, 0, -1) // sync call wrapped in IO
        jsonList.reversed().mapNotNull { jsonStr ->
            try {
                LiveEventJson.decodeFromString<LiveEvent>(jsonStr)
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
    * Deletes room history (cleanup when room ends).
    */
    fun deleteHistory(roomId: String) {
        producerCommands.del("room:$roomId:history")
    }

    /**
    * Increments a counter value in Redis.
    */
    fun incrementCounter(key: String, value: Long): Long = producerCommands.incrby(key, value)

    /**
    * Increments user-specific like count for a room.
    */
    fun incrementUserLikeCount(roomId: String, userId: String, count: Long): Long {
        val key = "room:$roomId:user_likes:$userId"
        return incrementCounter(key, count)
    }

    /**
    * Gets counter value from Redis.
    */
    fun getCounter(key: String): Long? = producerCommands.get(key)?.toLongOrNull()

    /**
    * Cleans up all Redis data for a room when it ends.
    */
    fun deleteCounters(roomId: String) {
        producerCommands.del("room:$roomId:likes")
        val userKeys = producerCommands.keys("room:$roomId:user_likes:*")
        if (userKeys.isNotEmpty()) {
            producerCommands.del(*userKeys.toTypedArray())
        }
        deleteHistory(roomId)
    }

    /**
    * Gets stream metadata from Redis hash.
    */
    fun getStreamMetadata(roomId: String): Map<String, String> = producerCommands.hgetall("stream:$roomId")

    /**
    * Gets stream moderation state.
    */
    fun getStreamModerationState(streamId: String): String? {
        return try {
            val metadata = getStreamMetadata(streamId)
            metadata["state"]
        } catch (e: Exception) {
            null
        }
    }

    /**
    * Closes Redis connections.
    */
    fun close() {
        try { producerConnection.close() } catch (_: Throwable) {}
        try { consumerConnection.close() } catch (_: Throwable) {}
        try { redisClient.shutdown() } catch (_: Throwable) {}
    }

    /**
     * Consume all new events from the "live_events" stream,
     *      broadcast them into the appropriate room, and ACK them.
     * Main consumer loop for live events - broadcasts events to WebSocket sessions.
     * This runs in a separate coroutine to continuously process events.
     */
    suspend fun consumeEvents(
        consumerGroup: String,
        crossInstanceBroadcaster: CrossInstanceBroadcaster
    ) {
        val streamKey = "live_events"

        // Create the consumer group if it doesn't exist
        try {
            consumerCommands.xgroupCreate(
                XReadArgs.StreamOffset.from(streamKey, "0-0"),
                consumerGroup,
                XGroupCreateArgs.Builder.mkstream(true)
            )
        } catch (e: RedisCommandExecutionException) {
            // Ignore BUSYGROUP ("group already exists")
            if (!e.message.orEmpty().contains("BUSYGROUP")) {
                throw e
            }
        }

        // Continuous consumption loop
        while (true) {
            try {
                // BLOCK up to 5s, read only new messages (">").
                val messages: List<StreamMessage<String, String>> =
                    consumerCommands.xreadgroup(
                        Consumer.from(consumerGroup, consumerId),
                        XReadArgs.Builder.block(5_000).count(100),
                        XReadArgs.StreamOffset.from(streamKey, ">")
                    )

                if (messages.isEmpty()) {
                    // no new events → small back-off
                    delay(100)
                    continue
                }

                // Process each message
                for (msg in messages) {
                    val json = msg.body["event"] ?: continue
                    println("consumeEvents: got message id=${msg.id} rawJson=${json.take(400)}")
                    try {
                        val event = LiveEventJson.decodeFromString<LiveEvent>(json)
                        println("consumeEvents: decoded eventType=${event::class.simpleName} roomId=${event.roomId}")
                        crossInstanceBroadcaster.broadcastToRoom(event.roomId, event)
                        // Acknowledge after successful broadcast
                        consumerCommands.xack(streamKey, consumerGroup, msg.id)
                    } catch (_: Throwable) {
                        // on JSON decode or broadcast failure: skip ack so we can retry later
                    }
                }
            } catch (e: RedisException) {
                // e.g. connection issue → retry after a pause
                delay(1_000)
            } catch (e: Throwable) {
                // any other failure → avoid tight loop
                delay(1_000)
            }
        }
    }
}
