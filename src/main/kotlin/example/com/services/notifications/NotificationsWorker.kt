package example.com.services.notifications

import example.com.config.AppJson
import example.com.ProfileUpdateType
import example.com.SocialEventType
import example.com.routes.dtos.NotificationEvent
import example.com.routes.dtos.withDefaults
import example.com.schemas.NotificationSchema
import example.com.schemas.UserSchema
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
    private val XREAD_BLOCK_MS = 500L       // reduced from 5000
    private val IDLE_DELAY_MS = 50L         // small idle delay when no messages
    private val NOTIF_POOL_THREADS = 8      // thread pool for notification processing
    private val CHUNK_SIZE = 200            // chunk followers into batches
    private val PROCESS_CONCURRENCY = 4     // how many message processing coroutines to run in parallel

    // Dedicated dispatcher for heavy notification processing (DB inserts + sends)
    private val notifDispatcher = Executors.newFixedThreadPool(NOTIF_POOL_THREADS).asCoroutineDispatcher()

    // helper to await a Lettuce RedisFuture when you don't have kotlinx-coroutines-jdk8:
    private suspend fun <T> awaitFuture(redisFuture: io.lettuce.core.RedisFuture<T>): T =
        suspendCoroutine { cont ->
            when {
                redisFuture.isDone -> {
                    try {
                        cont.resume(redisFuture.get())
                    } catch (e: Exception) {
                        cont.resumeWithException(e)
                    }
                }
                redisFuture.isCancelled -> cont.resumeWithException(CancellationException("RedisFuture cancelled"))
                else -> {
                    redisFuture.handle { res, err ->
                        if (err != null) cont.resumeWithException(err) else cont.resume(res)
                    }
                }
            }
        }

    /**
     * Main worker loop that continuously consumes events from Redis stream.
     * This function is suspendable. Prefer launching it on Dispatchers.IO:
     * CoroutineScope(Dispatchers.IO).launch { worker.run() }
     */
    suspend fun run() {
        // Ensure consumer group exists (redisService exposes an async create helper)
        try {
            redisService.createConsumerGroupIfNotExists(streamKey, consumerGroup)
        } catch (e: Exception) {
            println("DEBUG: createConsumerGroupIfNotExists error: ${e.message}")
        }

        // Small semaphore to bound parallelism of processing messages from each xread chunk
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
                    awaitFuture(future)
                } catch (e: Exception) {
                    println("DEBUG: xreadgroup await failed: ${e.message}")
                    null
                }

                if (messages.isNullOrEmpty()) {
                    delay(IDLE_DELAY_MS)
                    continue
                }

                // Process messages with bounded concurrency
                coroutineScope {
                    for (msg in messages) {
                        processSemaphore.withPermit {
                            launch {
                                try {
                                    processMessage(msg)
                                } catch (e: Exception) {
                                    println("DEBUG: Failed to process notification message ${msg.id}: ${e.message}")
                                    // If the message is malformed, ack it so it doesn't block.
                                    // But for transient failures we leave it unacked to retry.
                                    try {
                                        val raw = msg.body["event"]
                                        if (raw == null) {
                                            // malformed: ack and skip
                                            awaitFuture(redisService.consumerCommands.xack(streamKey, consumerGroup, msg.id))
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
     * Single message processing - dispatches to appropriate handlers and ACKs when done.
     */
    private suspend fun processMessage(msg: StreamMessage<String, String>) {
        val raw = msg.body["event"]
        if (raw == null) {
            // Acknowledge invalid messages to avoid infinite retries
            awaitFuture(redisService.consumerCommands.xack(streamKey, consumerGroup, msg.id))
            return
        }

        val event: SocialEvent = try {
            json.decodeFromString(SocialEvent.serializer(), raw)
        } catch (e: Exception) {
            // malformed payload -> ack and skip
            println("DEBUG: Malformed social event JSON: ${e.message}")
            awaitFuture(redisService.consumerCommands.xack(streamKey, consumerGroup, msg.id))
            return
        }

        try {
            when (event.type) {
                // FOLLOW
                SocialEventType.FOLLOW -> {
                    val actorId = event.actorId.toIntOrNull()
                    val targetId = event.targetId.toIntOrNull()
                    if (actorId == null || targetId == null) {
                        awaitFuture(redisService.consumerCommands.xack(streamKey, consumerGroup, msg.id))
                        return
                    }
                    handleFollowEvent(event, actorId, targetId)
                    awaitFuture(redisService.consumerCommands.xack(streamKey, consumerGroup, msg.id))
                }

                // UNFOLLOW
                SocialEventType.UNFOLLOW -> {
                    val actorId = event.actorId.toIntOrNull()
                    val targetId = event.targetId.toIntOrNull()
                    if (actorId == null || targetId == null) {
                        awaitFuture(redisService.consumerCommands.xack(streamKey, consumerGroup, msg.id))
                        return
                    }
                    handleUnfollowEvent(event, actorId, targetId)
                    awaitFuture(redisService.consumerCommands.xack(streamKey, consumerGroup, msg.id))
                }

                // LIVE_STARTED
                SocialEventType.LIVE_STARTED -> {
                    val actorId = event.actorId.toIntOrNull()
                    if (actorId == null) {
                        awaitFuture(redisService.consumerCommands.xack(streamKey, consumerGroup, msg.id))
                        return
                    }
                    handleLiveStartedEvent(event, actorId)
                    awaitFuture(redisService.consumerCommands.xack(streamKey, consumerGroup, msg.id))
                }

                // BLOCK or unknown
                else -> {
                    // ack and skip
                    awaitFuture(redisService.consumerCommands.xack(streamKey, consumerGroup, msg.id))
                }
            }
        } catch (e: Exception) {
            // if processing threw, do not ack to allow retry (unless you want to move to DLQ)
            println("DEBUG: exception in processMessage for id=${msg.id}: ${e.message}")
            // Optionally: after N retries move to DLQ and ack original.
        }
    }

    /**
     * Sends notifications to all followers in bounded chunks using notifDispatcher.
     */
    private suspend fun handleLiveStartedEvent(event: SocialEvent, actorIdInt: Int) {
        // DB lookup (may be blocking) -> do on IO
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

        // Process followers in chunks on notifDispatcher to avoid blocking the consumer
        followerIds.chunked(CHUNK_SIZE).forEach { chunk ->
            withContext(notifDispatcher) {
                chunk.forEach { followerId ->
                    // Insert notification into DB (do in IO)
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

                        // Build realtime event and attempt to send
                        val liveEvent = NotificationEvent.UserIsLive(
                            userId = followerId.toString(),
                            actorId = event.actorId,
                            actorUsername = liveUser.username,
                            actorAvatarUrl = avatarUrl,
                            text = storedText
                        ).withDefaults()

                        val eventJson = json.encodeToString(liveEvent)
                        // Try best-effort: sendToUser is suspend; if user is offline this is fast (returns false)
                        NotificationSessionRegistry.sendToUser(followerId, eventJson)
                    } catch (e: Exception) {
                        println("DEBUG: Failed notifying follower $followerId: ${e.message}")
                        // continue with others
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

        // Store notification and send realtime notification
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

        // Send profile updates (do in IO)
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
}