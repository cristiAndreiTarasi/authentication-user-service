package example.com.services.ws_session

import example.com.config.AppJson
import example.com.config.awaitFuture
import example.com.routes.dtos.LiveEvent
import example.com.routes.dtos.NotificationEvent
import example.com.routes.dtos.withDefaults
import example.com.services.notifications.NotificationSessionRegistry
import example.com.services.redis.RedisService
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
class CrossInstanceBroadcaster(
    private val redisService: RedisService,
    private val instanceId: String
) {
    private val json: Json = AppJson

    // Using Pub/Sub channels instead of Streams for real-time events
    private val ROOM_EVENTS_PREFIX = "room_events:"
    private val USER_NOTIFICATIONS_CHANNEL = "user_notifications"

    // Keep Stream for cross-instance control messages (rare, need persistence)
    private val CROSS_INSTANCE_CONTROL_STREAM = "cross_instance_control"

    // Scope used for listener & background tasks
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pubSubListenerJob: Job? = null
    private var controlStreamJob: Job? = null
    private var isRunning: Boolean = false

    // Tunables for broadcasting
    private val BROADCAST_CONCURRENCY = 100

    // Create dedicated Pub/Sub connections
    private val pubSubConnection: StatefulRedisPubSubConnection<String, String> = redisService.redisClient.connectPubSub()


    /**
     * CHANGE: Starts Pub/Sub listener for real-time cross-instance events
     * Replaces the Streams-based listener for better real-time performance
     */
    suspend fun startCrossInstanceListener() {
        // Start Pub/Sub listener for real-time events
        pubSubListenerJob = scope.launch {
            isRunning = true
            startPubSubListener()
        }

        // Keep Stream listener only for control messages (room cleanup, etc.)
        controlStreamJob = scope.launch {
            listenForControlMessages()
        }
    }

    /**
     * Pub/Sub listener for real-time event broadcasting
     * Uses pattern subscription to listen to all room events
     */
    private suspend fun startPubSubListener() {
        val pubSubAdapter = pubSubConnection.sync()

        try {
            // Subscribe to pattern for all room events
            pubSubAdapter.psubscribe("${ROOM_EVENTS_PREFIX}*")

            pubSubConnection.addListener(object : RedisPubSubListener<String, String> {
                override fun message(channel: String, message: String) {
                    // Handle direct channel subscriptions
                    if (channel.startsWith(ROOM_EVENTS_PREFIX)) {
                        scope.launch {
                            handleRoomEventMessage(channel, message)
                        }
                    }
                }

                override fun message(pattern: String, channel: String, message: String) {
                    if (pattern == "${ROOM_EVENTS_PREFIX}*") {
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

                override fun unsubscribed(channel: String, count: Long) {
                    println("DEBUG: Unsubscribed from $channel, total: $count")
                }

                override fun punsubscribed(pattern: String, count: Long) {
                    println("DEBUG: Pattern unsubscribed from $pattern, total: $count")
                }
            })

            println("DEBUG: Pub/Sub listener started for pattern ${ROOM_EVENTS_PREFIX}*")

            // Keep the listener alive
            while (isActive) {
                delay(10000) // Just keep alive
            }
        } catch (e: Exception) {
            println("DEBUG: Pub/Sub listener error: ${e.message}")
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
            val roomId = channel.removePrefix(ROOM_EVENTS_PREFIX)
            // Parse the cross-instance message
            val crossMessage = json.decodeFromString<CrossInstanceMessage>(message)

            // Ignore our own messages
            if (crossMessage.senderInstance == instanceId) {
                return
            }

            // Broadcast to local sessions in the room
            broadcastToLocalRoom(roomId, crossMessage.payload)

        } catch (e: Exception) {
            println("DEBUG: Error handling room event message: ${e.message}")
        }
    }

    /**
     * Use Pub/Sub for real-time events and Streams for durable events
     * Real-time events: likes, joins, leaves, chat messages (delivery)
     * Durable events: chat messages (persistence), gifts, moderation actions
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
            // Use Pub/Sub for real-time events
            redisService.producerCommands.publish(
                "${ROOM_EVENTS_PREFIX}$roomId",
                messageJson
            ).awaitFuture()
        } catch (e: Exception) {
            println("DEBUG: Failed to publish room event: ${e.message}")
        }

        // Use Streams ONLY for durable events that need persistence
        when (event) {
            is LiveEvent.ChatMessage -> {
                // Durable: Persist chat messages for moderation and analytics
                redisService.addToModerationStream(event)
                redisService.addToAnalyticsStream(event)
            }
            is LiveEvent.Gift -> {
                // Durable: Gift events for billing and analytics
                redisService.addToBillingStream(event)
                redisService.addToAnalyticsStream(event)
            }
            is LiveEvent.Like -> {
                // Ephemeral: Likes are real-time only (unless analytics needs them)
                if (event.count > 10) {
                    redisService.addToAnalyticsStream(event)
                }
            }
            is LiveEvent.KickUser, is LiveEvent.MuteUser, is LiveEvent.UnmuteUser,
            is LiveEvent.GrantModerator, is LiveEvent.RevokeModerator -> {
                // Durable: Moderation actions for audit log
                redisService.addToModerationStream(event)
            }

            else -> {}
            // Ephemeral events (joins, leaves, system messages) don't go to Streams
        }
    }

    /**
     * Modified to use Pub/Sub for user notifications
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
                // CHANGE: Using Pub/Sub instead of publish command
                redisService.producerCommands.publish(
                    USER_NOTIFICATIONS_CHANNEL,
                    messageJson
                ).awaitFuture()
            } catch (e: Exception) {
                println("DEBUG: Failed to publish user notification: ${e.message}")
            }
        }
    }

    /**
     * Listen for user notifications via Pub/Sub
     */
    private fun listenForUserNotifications() {
        val pubSubAdapter = pubSubConnection.sync()
        pubSubAdapter.subscribe(USER_NOTIFICATIONS_CHANNEL)
    }

    private suspend fun handleUserNotification(message: String) {
        try {
            val crossMessage = json.decodeFromString<CrossInstanceMessage>(message)

            // Ignore our own messages
            if (crossMessage.senderInstance == instanceId) return

            val targetUserId = crossMessage.targetUserId?.toIntOrNull() ?: return

            // Deliver to local session if exists
            NotificationSessionRegistry.sendToUser(targetUserId, crossMessage.payload)
        } catch (e: Exception) {
            println("DEBUG: Error handling user notification: ${e.message}")
        }
    }

    /**
     * Listen for control messages via Streams (rare, need persistence)
     */
    private suspend fun listenForControlMessages() {
        try {
            redisService.createConsumerGroupIfNotExists(CROSS_INSTANCE_CONTROL_STREAM, "control_group")
        } catch (e: Exception) {
            // Group likely exists
        }

        while (isActive) {
            try {
                val messages = redisService.consumerCommands.xreadgroup(
                    Consumer.from("control_group", instanceId),
                    XReadArgs.Builder.block(500).count(10),
                    XReadArgs.StreamOffset.from(CROSS_INSTANCE_CONTROL_STREAM, ">")
                ).awaitFuture()

                messages?.forEach { msg ->
                    processControlMessage(msg)
                }
            } catch (e: Exception) {
                delay(1000)
            }
        }
    }

    private suspend fun processControlMessage(msg: StreamMessage<String, String>) {
        // Handle room cleanup, instance shutdown, etc.
        // These are rare but need persistence
        redisService.consumerCommands.xack(
            CROSS_INSTANCE_CONTROL_STREAM,
            "control_group",
            msg.id
        ).awaitFuture()
    }

    /**
     * Broadcasts to local users only (bounded concurrency)
     */
    private suspend fun broadcastToLocalRoom(roomId: String, eventJson: String) = coroutineScope {
        val sessions = LiveRoomSessionRegistry.getRoomSessions(roomId)
        if (sessions.isEmpty()) return@coroutineScope

        val sem = Semaphore(BROADCAST_CONCURRENCY)
        val jobs = sessions.map { session ->
            async {
                sem.withPermit {
                    try {
                        session.send(Frame.Text(eventJson))
                    } catch (e: Exception) {
                        LiveRoomSessionRegistry.removeSession(session)
                    }
                }
            }
        }
        jobs.forEach { it.join() } // wait for sends to complete (bounded)
    }

    /**
     * Stop the cross-instance listener
     */
    fun stop() {
        pubSubListenerJob?.cancel()
        controlStreamJob?.cancel()
        pubSubConnection.close()
        scope.cancel()
    }

    /**
     * Check if the broadcaster is running
     */
    fun isRunning(): Boolean = isRunning
}