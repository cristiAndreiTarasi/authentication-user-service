package example.com.services.redis

import example.com.LiveEventJson
import example.com.routes.dtos.LiveEvent
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
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.api.sync.RedisCommands
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Duration
import java.util.concurrent.TimeUnit

class RedisManager(redisUrl: String) {
    private val redisClient: RedisClient = RedisClient.create(redisUrl)

    // Separate connections for producers and consumers
    private val producerConnection: StatefulRedisConnection<String, String> = redisClient.connect()
    private val consumerConnection: StatefulRedisConnection<String, String> = redisClient.connect()

    private val producerCommands: RedisCommands<String, String> = producerConnection.sync()
    private val consumerCommands: RedisCommands<String, String> = consumerConnection.sync()

    // Use a stable consumer ID (e.g. pod hostname in k8s)
    private val consumerId = "consumer-${System.getenv("HOSTNAME") ?: "default"}"

    fun addToStream(event: LiveEvent) {
        val json = LiveEventJson.encodeToString(event)
        producerCommands.xadd("live_events", mapOf("event" to json))
    }

    fun incrementCounter(key: String, value: Long): Long =
        producerCommands.incrby(key, value)

    fun incrementUserLikeCount(roomId: String, userId: String, count: Long): Long {
        val key = "room:$roomId:user_likes:$userId"
        return incrementCounter(key, count)
    }

    fun getCounter(key: String): Long? =
        producerCommands.get(key)?.toLongOrNull()

    private val MAX_HISTORY = 100

    fun addToHistory(roomId: String, event: LiveEvent) {
        // Only store chat and system messages
        if (event is LiveEvent.ChatMessage || event is LiveEvent.SystemMessage) {
            val key = "room:$roomId:history"
            val json = LiveEventJson.encodeToString(event)
            producerCommands.lpush(key, json)
            producerCommands.ltrim(key, 0, (MAX_HISTORY - 1).toLong())
        }
    }

    suspend fun getRoomHistory(roomId: String): List<LiveEvent> {
        val key = "room:$roomId:history"
        val jsonList = producerCommands.lrange(key, 0, -1)

        return jsonList.reversed().mapNotNull { json ->
            try {
                LiveEventJson.decodeFromString<LiveEvent>(json)
            } catch (e: Exception) {
                null
            }
        }
    }

    fun deleteHistory(roomId: String) {
        producerCommands.del("room:$roomId:history")
    }

    // Update deleteCounters to include history
    fun deleteCounters(roomId: String) {
        producerCommands.del("room:$roomId:likes")
        val userKeys = producerCommands.keys("room:$roomId:user_likes:*")
        if (userKeys.isNotEmpty()) {
            producerCommands.del(*userKeys.toTypedArray())
        }
        deleteHistory(roomId)
    }

    fun close() {
        producerConnection.close()
        consumerConnection.close()
        redisClient.shutdown()
    }

    /**
     * Consume all new events from the "live_events" stream,
     * broadcast them into the appropriate room, and ACK them.
     */
    suspend fun consumeEvents(consumerGroup: String) {
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
                    // no new events → small back‑off
                    delay(100)
                    continue
                }

                for (msg in messages) {
                    val json = msg.body["event"] ?: continue
                    try {
                        val event = LiveEventJson.decodeFromString<LiveEvent>(json)
                        broadcastToRoom(event.roomId, event)
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

/** Broadcast into all WebSocketSessions in the given room */
private suspend fun broadcastToRoom(roomId: String, event: LiveEvent) {
    val json = LiveEventJson.encodeToString(event)

    SessionManager.getRoomSessions(roomId).forEach { session ->
        try {
            session.send(Frame.Text(json))
        } catch (_: Throwable) {
            SessionManager.removeSession(session)
        }
    }
}