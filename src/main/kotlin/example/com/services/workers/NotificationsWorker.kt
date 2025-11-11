package example.com.services.workers

import example.com.config.AppJson
import example.com.ProfileUpdateType
import example.com.SocialEventType
import example.com.config.awaitFuture
import example.com.routes.dtos.NotificationEvent
import example.com.routes.dtos.withDefaults
import example.com.schemas.NotificationSchema
import example.com.schemas.UserSchema
import example.com.services.notifications.NotificationSessionRegistry
import example.com.services.redis.RedisService
import example.com.services.redis.SocialEvent
import io.lettuce.core.Consumer
import io.lettuce.core.StreamMessage
import io.lettuce.core.XReadArgs
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.Executors
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
* Background worker that consumes social events from Redis streams
* and processes them into real-time notifications.
*
* This worker runs continuously and handles:
* - Follow/unfollow events
* - Live stream started notifications
* - Profile update events
*/
class NotificationWorker(
    private val redisService: RedisService,
    private val notificationSchema: NotificationSchema,
    private val userSchema: UserSchema,
    private val consumerGroup: String = "notifications-group",
    private val consumerId: String = "notif-consumer-${System.getenv("HOSTNAME") ?: "local"}",
    private val streamKey: String = "social_events"
) {
    private val json: Json = AppJson

    // Tunables — experiment with these numbers
    private val XREAD_BLOCK_MS = 500L
    private val IDLE_DELAY_MS = 50L
    private val NOTIF_POOL_THREADS = 8
    private val CHUNK_SIZE = 200
    private val PROCESS_CONCURRENCY = 4

    // Dedicated dispatcher for heavy notification processing
    private val notifDispatcher = Executors.newFixedThreadPool(NOTIF_POOL_THREADS).asCoroutineDispatcher()

    /**
     * Main worker loop that continuously consumes events from Redis social stream
     */
    suspend fun run() {
        println("🔔 NotificationWorker starting for Redis role: ${redisService.redisRole}")

        // Ensure consumer group exists
        try {
            redisService.createConsumerGroupIfNotExists(streamKey, consumerGroup)
        } catch (e: Exception) {
            println("DEBUG: createConsumerGroupIfNotExists error: ${e.message}")
        }

        val processSemaphore = Semaphore(PROCESS_CONCURRENCY)

        while (true) {
            try {
                // Use async xreadgroup and await result
                val future = redisService.consumerCommands.xreadgroup(
                    Consumer.from(consumerGroup, consumerId),
                    XReadArgs.Builder.block(XREAD_BLOCK_MS).count(100),
                    XReadArgs.StreamOffset.from(streamKey, ">")
                )

                val messages: List<StreamMessage<String, String>>? = try {
                    future.awaitFuture()
                } catch (e: Exception) {
                    println("DEBUG: xreadgroup await failed: ${e.message}")
                    null
                }

                if (messages.isNullOrEmpty()) {
                    delay(IDLE_DELAY_MS)
                    continue
                }

                println("🔔 Processing ${messages.size} social events from ${redisService.redisRole}")

                // Process messages with bounded concurrency
                coroutineScope {
                    for (msg in messages) {
                        processSemaphore.withPermit {
                            launch {
                                try {
                                    processMessage(msg)
                                } catch (e: Exception) {
                                    println("DEBUG: Failed to process notification message ${msg.id}: ${e.message}")
                                    try {
                                        val raw = msg.body["event"]
                                        if (raw == null) {
                                            redisService.consumerCommands.xack(streamKey, consumerGroup, msg.id).awaitFuture()
                                        }
                                    } catch (ackEx: Exception) {
                                        println("DEBUG: Failed to ACK malformed message ${msg.id}: ${ackEx.message}")
                                    }
                                }
                            }
                        }
                    }
                }

            } catch (e: Exception) {
                println("DEBUG: NotificationWorker run loop error: ${e.message}")
                delay(1000)
            }
        }
    }

    /**
     * Single message processing - dispatches to appropriate handlers and ACKs when done
     */
    private suspend fun processMessage(msg: StreamMessage<String, String>) {
        val raw = msg.body["event"]
        if (raw == null) {
            redisService.consumerCommands.xack(streamKey, consumerGroup, msg.id).awaitFuture()
            return
        }

        val event: SocialEvent = try {
            json.decodeFromString(SocialEvent.serializer(), raw)
        } catch (e: Exception) {
            println("DEBUG: Malformed social event JSON: ${e.message}")
            redisService.consumerCommands.xack(streamKey, consumerGroup, msg.id).awaitFuture()
            return
        }

        try {
            when (event.type) {
                SocialEventType.FOLLOW -> {
                    val actorId = event.actorId.toIntOrNull()
                    val targetId = event.targetId.toIntOrNull()
                    if (actorId == null || targetId == null) {
                        redisService.consumerCommands.xack(streamKey, consumerGroup, msg.id).awaitFuture()
                        return
                    }
                    handleFollowEvent(event, actorId, targetId)
                    redisService.consumerCommands.xack(streamKey, consumerGroup, msg.id).awaitFuture()
                }

                SocialEventType.UNFOLLOW -> {
                    val actorId = event.actorId.toIntOrNull()
                    val targetId = event.targetId.toIntOrNull()
                    if (actorId == null || targetId == null) {
                        redisService.consumerCommands.xack(streamKey, consumerGroup, msg.id).awaitFuture()
                        return
                    }
                    handleUnfollowEvent(event, actorId, targetId)
                    redisService.consumerCommands.xack(streamKey, consumerGroup, msg.id).awaitFuture()
                }

                SocialEventType.LIVE_STARTED -> {
                    val actorId = event.actorId.toIntOrNull()
                    if (actorId == null) {
                        redisService.consumerCommands.xack(streamKey, consumerGroup, msg.id).awaitFuture()
                        return
                    }
                    handleLiveStartedEvent(event, actorId)
                    redisService.consumerCommands.xack(streamKey, consumerGroup, msg.id).awaitFuture()
                }

                else -> {
                    redisService.consumerCommands.xack(streamKey, consumerGroup, msg.id).awaitFuture()
                }
            }
        } catch (e: Exception) {
            println("DEBUG: exception in processMessage for id=${msg.id}: ${e.message}")
        }
    }

    /**
     * Sends notifications to all followers in bounded chunks using notifDispatcher
     */
    private suspend fun handleLiveStartedEvent(event: SocialEvent, actorIdInt: Int) {
        val liveUser = withContext(Dispatchers.IO) {
            userSchema.findById(actorIdInt)
        } ?: run {
            println("DEBUG: live user not found: $actorIdInt")
            return
        }

        val followersTally = withContext(Dispatchers.IO) {
            userSchema.getUserFollowers(actorIdInt)
        }
        val followerIds = followersTally.userIds

        val storedText = "${liveUser.username} is now live!"
        val avatarUrl = "/users/fetch/${actorIdInt}/avatar"
        val streamId = event.meta["streamId"] ?: ""

        followerIds.chunked(CHUNK_SIZE).forEach { chunk ->
            withContext(notifDispatcher) {
                chunk.forEach { followerId ->
                    try {
                        runBlocking(Dispatchers.IO) {
                            notificationSchema.insertNotification(
                                userId = followerId,
                                actorId = actorIdInt,
                                type = "user_is_live",
                                text = storedText,
                                meta = mapOf(
                                    "actorUsername" to liveUser.username,
                                    "actorAvatarUrl" to avatarUrl,
                                    "streamId" to streamId
                                )
                            )
                        }

                        val liveEvent = NotificationEvent.UserIsLive(
                            userId = followerId.toString(),
                            actorId = event.actorId,
                            actorUsername = liveUser.username,
                            actorAvatarUrl = avatarUrl,
                            text = storedText
                        ).withDefaults()

                        val eventJson = json.encodeToString(liveEvent)
                        NotificationSessionRegistry.sendToUser(followerId, eventJson)
                    } catch (e: Exception) {
                        println("DEBUG: Failed notifying follower $followerId: ${e.message}")
                    }
                }
            }
        }
    }

    private suspend fun handleFollowEvent(event: SocialEvent, actorIdInt: Int, targetIdInt: Int) {
        val actorUsername = event.actorUsername ?: withContext(Dispatchers.IO) {
            userSchema.findById(actorIdInt)?.username ?: "Someone"
        }

        val storedText = "$actorUsername started following you"
        val avatarUrl = "/users/fetch/${actorIdInt}/avatar"

        withContext(Dispatchers.IO) {
            notificationSchema.insertNotification(
                userId = targetIdInt,
                actorId = actorIdInt,
                type = "follow",
                text = storedText,
                meta = mapOf("actorUsername" to actorUsername)
            )
        }

        val followEvent = NotificationEvent.Follow(
            userId = targetIdInt.toString(),
            actorId = event.actorId,
            actorUsername = actorUsername,
            actorAvatarUrl = avatarUrl,
            text = storedText
        ).withDefaults()

        val eventJson = json.encodeToString(followEvent)
        NotificationSessionRegistry.sendToUser(targetIdInt, eventJson)

        sendProfileUpdate(targetIdInt, ProfileUpdateType.FOLLOWERS)
        sendProfileUpdate(actorIdInt, ProfileUpdateType.FOLLOWING)
    }

    private suspend fun handleUnfollowEvent(event: SocialEvent, actorIdInt: Int, targetIdInt: Int) {
        sendProfileUpdate(targetIdInt, ProfileUpdateType.FOLLOWERS)
        sendProfileUpdate(actorIdInt, ProfileUpdateType.FOLLOWING)
    }

    private suspend fun sendProfileUpdate(userId: Int, updateType: ProfileUpdateType) {
        val (count, userIds) = withContext(Dispatchers.IO) {
            when (updateType) {
                ProfileUpdateType.FOLLOWERS -> {
                    val result = userSchema.getUserFollowers(userId)
                    result.tally to result.userIds
                }
                ProfileUpdateType.FOLLOWING -> {
                    val result = userSchema.getUserFollowing(userId)
                    result.tally to result.userIds
                }
                ProfileUpdateType.UNREAD_COUNT -> {
                    notificationSchema.getUnreadCount(userId) to null
                }
                ProfileUpdateType.LIKES -> TODO()
            }
        }

        val profileEvent = NotificationEvent.ProfileUpdate(
            userId = userId.toString(),
            updateType = updateType,
            count = count,
            userIds = userIds
        ).withDefaults()

        val eventJson = json.encodeToString(profileEvent)
        NotificationSessionRegistry.sendToUser(userId, eventJson)
    }

    /**
     * Health check for NotificationWorker
     */
    suspend fun isHealthy(): Boolean {
        return try {
            redisService.ping() && notificationSchema.getUnreadCount(1) >= 0 // Simple DB check
        } catch (e: Exception) {
            false
        }
    }
}