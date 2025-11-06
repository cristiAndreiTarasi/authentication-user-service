package example.com.services.ws_session

import example.com.LiveEventJson
import example.com.NotificationEventJson
import example.com.routes.dtos.LiveEvent
import example.com.routes.dtos.NotificationEvent
import example.com.routes.dtos.withDefaults
import example.com.services.notifications.NotificationSessionRegistry
import example.com.services.redis.RedisService
import io.ktor.websocket.Frame
import io.lettuce.core.Consumer
import io.lettuce.core.StreamMessage
import io.lettuce.core.XReadArgs
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable.isActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.launch

@Serializable
data class CrossInstanceMessage(
    val type: String, // "live_event" or "notification_event"
    val roomId: String? = null,
    val targetUserId: String? = null,
    val senderInstance: String,
    val payload: String,
    val timestamp: Long
)

/**
* Handles cross-instance event broadcasting using Redis Streams
* Ensures events reach all users across all instances
*/
class CrossInstanceBroadcaster(
    private val redisService: RedisService,
    private val instanceId: String
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val CROSS_INSTANCE_STREAM = "cross_instance_stream"
    private val CONSUMER_GROUP = "cross_instance_group"

    private val scope = CoroutineScope(SupervisorJob())
    private var listenerJob: Job? = null
    private var isRunning: Boolean = false

    /**
    * Starts listening for cross-instance messages
    * Call this once per instance during startup
    */
    suspend fun startCrossInstanceListener() { // Remove unused parameter
        // Create consumer group if it doesn't exist
        try {
            redisService.createConsumerGroupIfNotExists(CROSS_INSTANCE_STREAM, CONSUMER_GROUP)
        } catch (e: Exception) {
            println("DEBUG: Consumer group already exists or error: ${e.message}")
        }

        // Start consuming from the stream
        listenerJob = scope.launch {
            isRunning = true
            consumeCrossInstanceMessages()
        }

        println("DEBUG: Cross-instance broadcaster (Streams) started for instance $instanceId")
    }

    /**
     * Separate consumption loop for better organization
     */
    private suspend fun consumeCrossInstanceMessages() {
        while (isActive) {
            try {
                val messages: List<StreamMessage<String, String>> =
                    redisService.consumerCommands.xreadgroup(
                        Consumer.from(CONSUMER_GROUP, instanceId),
                        XReadArgs.Builder.block(5000).count(100),
                        XReadArgs.StreamOffset.from(CROSS_INSTANCE_STREAM, ">")
                    )

                if (messages.isEmpty()) {
                    delay(100)
                    continue
                }

                for (msg in messages) {
                    try {
                        val messageType = msg.body["type"]
                        val payload = msg.body["payload"]
                        val senderInstance = msg.body["senderInstance"]
                        val roomId = msg.body["roomId"]

                        // Ignore our own messages
                        if (senderInstance == instanceId) {
                            redisService.consumerCommands.xack(CROSS_INSTANCE_STREAM, CONSUMER_GROUP, msg.id)
                            continue
                        }

                        when (messageType) {
                            "live_event" -> {
                                if (payload != null && roomId != null) {
                                    handleCrossInstanceLiveEvent(roomId, payload)
                                }
                            }
                            // Remove notification_event handling since we're using Pub/Sub for it
                        }

                        // Acknowledge message after successful processing
                        redisService.consumerCommands.xack(CROSS_INSTANCE_STREAM, CONSUMER_GROUP, msg.id)

                    } catch (e: Exception) {
                        println("DEBUG: Error processing cross-instance message: ${e.message}")
                        // Don't ack on error - let it be retried
                    }
                }
            } catch (e: Exception) {
                println("DEBUG: Error in cross-instance listener: ${e.message}")
                delay(1000)
            }
        }

        isRunning = false
    }

    /**
    * Broadcasts live events to all users in a room across all instances
    */
    suspend fun broadcastToRoom(roomId: String, event: LiveEvent) {
        val safeEvent = event.withDefaults()
        val eventJson = LiveEventJson.encodeToString(PolymorphicSerializer(LiveEvent::class), safeEvent)

        // Broadcast to local users first (for lowest latency)
        broadcastToLocalRoom(roomId, eventJson)

        // 2. Publish to Redis for other instances
        val crossInstanceMessage = CrossInstanceMessage(
            type = "live_event",
            roomId = roomId,
            senderInstance = instanceId,
            payload = eventJson,
            timestamp = System.currentTimeMillis()
        )

        val messageJson = json.encodeToString(CrossInstanceMessage.serializer(), crossInstanceMessage)
        redisService.producerCommands.xadd(
            CROSS_INSTANCE_STREAM,
            mapOf(
                "type" to "live_event",
                "roomId" to roomId,
                "senderInstance" to instanceId,
                "payload" to messageJson
            )
        )
    }

    /**
    * Broadcasts notification events to specific users across instances
    */
    suspend fun sendToUser(userId: Int, event: NotificationEvent) {
        val safeEvent = event.withDefaults()
        val eventJson = NotificationEventJson.encodeToString(safeEvent)

        // Try local delivery first
        val localDelivered = NotificationSessionRegistry.sendToUser(userId, eventJson)

        if (!localDelivered) {
            // User not local - publish for other instances
            val crossInstanceMessage = CrossInstanceMessage(
                type = "notification_event",
                targetUserId = userId.toString(),
                senderInstance = instanceId,
                payload = eventJson,
                timestamp = System.currentTimeMillis()
            )

            val messageJson = json.encodeToString(CrossInstanceMessage.serializer(), crossInstanceMessage)
            redisService.producerCommands.publish("cross_instance:user_message", messageJson)
        }
    }

    /**
    * Broadcasts to local users only
    */
    private suspend fun broadcastToLocalRoom(roomId: String, eventJson: String) {
        val sessions = SessionManager.getRoomSessions(roomId)
        sessions.forEach { session ->
            try {
                session.send(Frame.Text(eventJson))
            } catch (e: Exception) {
                // Remove dead sessions
                SessionManager.removeSession(session)
            }
        }
    }

    private suspend fun handleCrossInstanceLiveEvent(roomId: String, payload: String) {
        try {
            val crossMessage = json.decodeFromString<CrossInstanceMessage>(payload)

            // Broadcast to local users in the room
            val localSessions = SessionManager.getRoomSessions(roomId)
            localSessions.forEach { session ->
                try {
                    session.send(Frame.Text(crossMessage.payload))
                } catch (e: Exception) {
                    SessionManager.removeSession(session)
                }
            }
        } catch (e: Exception) {
            println("DEBUG: Error handling cross-instance live event: ${e.message}")
        }
    }

    /**
     * Stop the cross-instance listener
     */
    fun stop() {
        listenerJob?.cancel()
        scope.cancel()
    }

    /**
     * Check if the broadcaster is running
     */
    fun isRunning(): Boolean = isRunning
}