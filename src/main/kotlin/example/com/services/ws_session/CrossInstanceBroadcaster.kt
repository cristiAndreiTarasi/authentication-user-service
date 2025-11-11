package example.com.services.ws_session

import example.com.config.AppJson
import example.com.config.awaitFuture
import example.com.routes.dtos.LiveEvent
import example.com.routes.dtos.NotificationEvent
import example.com.routes.dtos.withDefaults
import example.com.services.notifications.NotificationSessionRegistry
import example.com.services.redis.EventCategory
import example.com.services.redis.RedisService
import example.com.services.redis.RedisStreams
import example.com.services.redis.getEventCategory
import io.ktor.websocket.Frame
import io.lettuce.core.Consumer
import io.lettuce.core.StreamMessage
import io.lettuce.core.XReadArgs
import io.lettuce.core.pubsub.RedisPubSubListener
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable.isActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
// CrossInstanceBroadcaster.kt
class CrossInstanceBroadcaster(
    private val redisService: RedisService,
    private val instanceId: String
) {
    private val json: Json = AppJson

    // Using Pub/Sub channels for real-time events
    private val pubSubConnection: StatefulRedisPubSubConnection<String, String> = redisService.redisClient.connectPubSub()

    // Scope used for listener & background tasks
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pubSubListenerJob: Job? = null
    private var isRunning: Boolean = false

    // Tunables for broadcasting
    private val BROADCAST_CONCURRENCY = 100

    /**
     * Starts Pub/Sub listener for real-time cross-instance events
     */
    suspend fun startCrossInstanceListener() {
        pubSubListenerJob = scope.launch {
            isRunning = true
            startPubSubListener()
        }
        println("DEBUG: Cross-instance Pub/Sub listener started for instance $instanceId")
    }

    /**
     * Pub/Sub listener for real-time event broadcasting
     */
    private suspend fun startPubSubListener() {
        val pubSubAdapter = pubSubConnection.sync()

        try {
            // Subscribe to pattern for all room events
            pubSubAdapter.psubscribe("${RedisStreams.ROOM_EVENTS_PREFIX}*")
            // Subscribe to user notifications channel
            pubSubAdapter.subscribe(RedisStreams.USER_NOTIFICATIONS_CHANNEL)

            pubSubConnection.addListener(object : RedisPubSubListener<String, String> {
                override fun message(channel: String, message: String) {
                    scope.launch {
                        when {
                            channel.startsWith(RedisStreams.ROOM_EVENTS_PREFIX) -> handleRoomEventMessage(channel, message)
                            channel == RedisStreams.USER_NOTIFICATIONS_CHANNEL -> handleUserNotification(message)
                        }
                    }
                }

                override fun message(pattern: String, channel: String, message: String) {
                    if (pattern == "${RedisStreams.ROOM_EVENTS_PREFIX}*") {
                        scope.launch {
                            handleRoomEventMessage(channel, message)
                        }
                    }
                }

                override fun subscribed(channel: String, count: Long) {
                    println("DEBUG: Subscribed to $channel, total: $count")
                }

                override fun psubscribed(pattern: String, count: Long) {
                    println("DEBUG: Pattern subscribed to $pattern, total: $count")
                }

                override fun unsubscribed(channel: String, count: Long) {}
                override fun punsubscribed(pattern: String, count: Long) {}
            })

            // Keep the listener alive
            while (isActive) {
                delay(30000) // Heartbeat every 30 seconds
                println("DEBUG: Cross-instance listener heartbeat - instance $instanceId")
            }
        } catch (e: Exception) {
            println("ERROR: Pub/Sub listener error: ${e.message}")
            // Attempt restart after delay
            delay(5000)
            if (isActive) {
                startPubSubListener()
            }
        }
    }

    /**
     * Handle incoming room events from Pub/Sub
     */
    private suspend fun handleRoomEventMessage(channel: String, message: String) {
        try {
            val roomId = channel.removePrefix(RedisStreams.ROOM_EVENTS_PREFIX)
            val crossMessage = json.decodeFromString<CrossInstanceMessage>(message)

            // Ignore our own messages
            if (crossMessage.senderInstance == instanceId) {
                return
            }

            println("DEBUG: Received cross-instance room event for room $roomId from ${crossMessage.senderInstance}")

            // Broadcast to local sessions in the room
            broadcastToLocalRoom(roomId, crossMessage.payload)

        } catch (e: Exception) {
            println("ERROR: Handling room event message: ${e.message}")
        }
    }

    /**
     * Handle incoming user notifications from Pub/Sub
     */
    private suspend fun handleUserNotification(message: String) {
        try {
            val crossMessage = json.decodeFromString<CrossInstanceMessage>(message)

            // Ignore our own messages
            if (crossMessage.senderInstance == instanceId) return

            val targetUserId = crossMessage.targetUserId?.toIntOrNull() ?: return

            println("DEBUG: Received cross-instance notification for user $targetUserId from ${crossMessage.senderInstance}")

            // Deliver to local session if exists
            val delivered = NotificationSessionRegistry.sendToUser(targetUserId, crossMessage.payload)
            if (delivered) {
                println("DEBUG: Successfully delivered notification to user $targetUserId")
            }

        } catch (e: Exception) {
            println("ERROR: Handling user notification: ${e.message}")
        }
    }

    /**
     * Unified method for broadcasting events with proper stream categorization
     */
    suspend fun broadcastToRoom(roomId: String, event: LiveEvent) {
        val safeEvent = event.withDefaults()
        val eventJson = json.encodeToString(PolymorphicSerializer(LiveEvent::class), safeEvent)

        // Always broadcast to local users first (lowest latency)
        broadcastToLocalRoom(roomId, eventJson)

        // Use Pub/Sub for cross-instance real-time delivery
        val crossInstanceMessage = CrossInstanceMessage(
            type = "live_event",
            roomId = roomId,
            senderInstance = instanceId,
            payload = eventJson,
            timestamp = System.currentTimeMillis()
        )

        val messageJson = json.encodeToString(CrossInstanceMessage.serializer(), crossInstanceMessage)

        try {
            redisService.publishToRoom(roomId, messageJson)
        } catch (e: Exception) {
            println("ERROR: Failed to publish room event: ${e.message}")
        }

        // Route to appropriate durable stream based on event category
        when (event.getEventCategory()) {
            EventCategory.CHAT,
            EventCategory.MODERATION,
            EventCategory.ANALYTICS,
            EventCategory.BILLING,
            EventCategory.SOCIAL,
            EventCategory.CONTROL -> {
                try {
                    redisService.addToCategorizedStream(event)
                } catch (e: Exception) {
                    println("ERROR: Failed to add event to categorized stream: ${e.message}")
                }
            }
            EventCategory.REAL_TIME_ONLY -> {
                // These events only go through Pub/Sub, not streams
                println("DEBUG: Real-time event ${event::class.simpleName} not persisted to stream")
            }
        }
    }

    /**
     * Send notification to user with proper routing
     */
    suspend fun sendToUser(userId: Int, event: NotificationEvent) {
        val safeEvent = event.withDefaults()
        val eventJson = json.encodeToString(safeEvent)

        // Try local delivery first
        val localDelivered = NotificationSessionRegistry.sendToUser(userId, eventJson)

        if (!localDelivered) {
            // Use Pub/Sub for cross-instance user notifications
            val crossInstanceMessage = CrossInstanceMessage(
                type = "notification_event",
                targetUserId = userId.toString(),
                senderInstance = instanceId,
                payload = eventJson,
                timestamp = System.currentTimeMillis()
            )

            val messageJson = json.encodeToString(CrossInstanceMessage.serializer(), crossInstanceMessage)
            try {
                redisService.publishToUser(userId.toString(), messageJson)
                println("DEBUG: Published cross-instance notification for user $userId")
            } catch (e: Exception) {
                println("ERROR: Failed to publish user notification: ${e.message}")
            }
        } else {
            println("DEBUG: Delivered notification locally to user $userId")
        }
    }

    /**
     * Broadcasts to local users only with bounded concurrency
     */
    private suspend fun broadcastToLocalRoom(roomId: String, eventJson: String) = coroutineScope {
        val sessions = LiveRoomSessionRegistry.getRoomSessions(roomId)
        if (sessions.isEmpty()) {
            println("DEBUG: No local sessions found for room $roomId")
            return@coroutineScope
        }

        println("DEBUG: Broadcasting to ${sessions.size} local sessions in room $roomId")

        val sem = Semaphore(BROADCAST_CONCURRENCY)
        val jobs = sessions.map { session ->
            async {
                sem.withPermit {
                    try {
                        session.send(Frame.Text(eventJson))
                    } catch (e: Exception) {
                        println("DEBUG: Failed to send to session, removing: ${e.message}")
                        LiveRoomSessionRegistry.removeSession(session)
                    }
                }
            }
        }
        jobs.forEach { it.await() }
    }

    /**
     * Stop the cross-instance listener
     */
    fun stop() {
        pubSubListenerJob?.cancel()
        pubSubConnection.close()
        scope.cancel()
        isRunning = false
        println("DEBUG: Cross-instance broadcaster stopped for instance $instanceId")
    }

    /**
     * Check if the broadcaster is running
     */
    fun isRunning(): Boolean = isRunning
}