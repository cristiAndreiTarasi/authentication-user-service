package example.com.services.notifications

import example.com.NotificationEventJson
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
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
    private val json = Json { ignoreUnknownKeys = true }

    /**
    * Main worker loop that continuously consumes events from Redis stream.
    */
    suspend fun run() {
        // Create consumer group if it doesn't exist
        try {
            redisService.createConsumerGroupIfNotExists(streamKey, consumerGroup)
        } catch (_: Exception) {
        }

        // Main consumption loop
        while (true) {
            try {
                // Read messages from Redis stream with 5 second block
                val messages: List<StreamMessage<String, String>> =
                    redisService.consumerCommands.xreadgroup(
                        Consumer.from(consumerGroup, consumerId),
                        XReadArgs.Builder.block(5000).count(100),
                        XReadArgs.StreamOffset.from(streamKey, ">") // ">" means new messages only
                    )

                if (messages.isEmpty()) {
                    delay(100)
                    continue
                }

                // Process each message
                for (msg in messages) {
                    val raw = msg.body["event"]
                    if (raw == null) {
                        // Acknowledge invalid messages to avoid reprocessing
                        redisService.consumerCommands.xack(streamKey, consumerGroup, msg.id)
                        continue
                    }

                    try {
                        val socialEvent = json.decodeFromString(SocialEvent.serializer(), raw)
                        handleSocialEvent(socialEvent, msg.id)
                    } catch (e: Exception) {
                        // Acknowledge problematic messages to avoid getting stuck
                        redisService.consumerCommands.xack(streamKey, consumerGroup, msg.id)
                    }
                }
            } catch (e: Exception) {
                println("DEBUG: NotificationWorker error: ${e.message}")
                delay(1000)
            }
        }
    }

    /**
    * Handles live started events - notifies all followers when a user goes live.
    */
    private suspend fun handleLiveStartedEvent(
        event: SocialEvent,
        actorIdInt: Int,
        messageId: String
    ) {
        // Get the user who went live
        val liveUser = userSchema.findById(actorIdInt) ?: run {
            redisService.consumerCommands.xack(streamKey, consumerGroup, messageId)
            return
        }

        // Get all followers of the user who went live
        val followersTally = userSchema.getUserFollowers(actorIdInt)
        val followerIds = followersTally.userIds

        val storedText = "${liveUser.username} is now live!"
        val avatarUrl = "/users/fetch/${actorIdInt}/avatar"

        // Send notifications to all followers
        followerIds.forEach { followerId ->
            // Store notification in database for persistence
            val stored = notificationSchema.insertNotification(
                userId = followerId,
                actorId = actorIdInt,
                type = "user_is_live",
                text = storedText,
                meta = mapOf(
                    "actorUsername" to liveUser.username,
                    "actorAvatarUrl" to avatarUrl,
                    "streamId" to (event.meta["streamId"] ?: "")
                )
            )

            // Send real-time notification via WebSocket
            val liveEvent = NotificationEvent.UserIsLive(
                userId = followerId.toString(),
                actorId = event.actorId,
                actorUsername = liveUser.username,
                actorAvatarUrl = avatarUrl,
                text = storedText
            ).withDefaults()

            val eventJson = NotificationEventJson.encodeToString(liveEvent)
            NotificationSessionRegistry.sendToUser(followerId, eventJson)
        }

        // Acknowledge message after processing
        redisService.consumerCommands.xack(streamKey, consumerGroup, messageId)
    }

    /**
    * Handles follow events - notifies the target user and updates follower counts.
    */
    private suspend fun handleFollowEvent(
        event: SocialEvent,
        actorIdInt: Int,
        targetIdInt: Int,
        messageId: String
    ) {
        val actorUsername = event.actorUsername ?: run {
            val user = userSchema.findById(actorIdInt)
            user?.username ?: "Someone"
        }

        val storedText = "$actorUsername started following you"
        val avatarUrl = "/users/fetch/${actorIdInt}/avatar"

        // Send real-time follow notification
        val followEvent = NotificationEvent.Follow(
            userId = targetIdInt.toString(),
            actorId = event.actorId,
            actorUsername = actorUsername,
            actorAvatarUrl = avatarUrl,
            text = storedText
        ).withDefaults()

        val eventJson = NotificationEventJson.encodeToString(followEvent)
        NotificationSessionRegistry.sendToUser(targetIdInt, eventJson)

        // Send profile updates to both users
        sendProfileUpdate(targetIdInt, ProfileUpdateType.FOLLOWERS)
        sendProfileUpdate(actorIdInt, ProfileUpdateType.FOLLOWING)

        redisService.consumerCommands.xack(streamKey, consumerGroup, messageId)
    }

    /**
    * Handles unfollow events - updates follower counts for both users.
    */
    private suspend fun handleUnfollowEvent(
        event: SocialEvent,
        actorIdInt: Int,
        targetIdInt: Int,
        messageId: String
    ) {
        // Send profile updates for unfollow
        sendProfileUpdate(targetIdInt, ProfileUpdateType.FOLLOWERS)
        sendProfileUpdate(actorIdInt, ProfileUpdateType.FOLLOWING)
        println("DEBUG: Processed unfollow event: actor=$actorIdInt, target=$targetIdInt")

        redisService.consumerCommands.xack(streamKey, consumerGroup, messageId)
    }

    /**
    * Sends profile update events to users (followers count, following count, unread count).
    */
    private suspend fun sendProfileUpdate(userId: Int, updateType: ProfileUpdateType) {
        // Get current count and user lists from database
        val (count, userIds) = when (updateType) {
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

        val profileEvent = NotificationEvent.ProfileUpdate(
            userId = userId.toString(),
            updateType = updateType,
            count = count,
            userIds = userIds // Include the user lists
        ).withDefaults()

        val eventJson = NotificationEventJson.encodeToString(profileEvent)
        NotificationSessionRegistry.sendToUser(userId, eventJson)
    }

    /**
    * Main event router - dispatches social events to appropriate handlers.
    */
    private suspend fun handleSocialEvent(event: SocialEvent, messageId: String) {
        val actorIdInt = event.actorId.toIntOrNull()
        val targetIdInt = event.targetId.toIntOrNull()

        when (event.type) {
            SocialEventType.FOLLOW -> {
                if (actorIdInt == null || targetIdInt == null) {
                    redisService.consumerCommands.xack(streamKey, consumerGroup, messageId)
                    return
                }
                handleFollowEvent(event, actorIdInt, targetIdInt, messageId)
            }
            SocialEventType.UNFOLLOW -> {
                if (actorIdInt == null || targetIdInt == null) {
                    redisService.consumerCommands.xack(streamKey, consumerGroup, messageId)
                    return
                }
                handleUnfollowEvent(event, actorIdInt, targetIdInt, messageId)
            }
            SocialEventType.LIVE_STARTED -> {
                if (actorIdInt == null) {
                    redisService.consumerCommands.xack(streamKey, consumerGroup, messageId)
                    return
                }
                handleLiveStartedEvent(event, actorIdInt, messageId)
            }
            SocialEventType.BLOCK -> {
                // Handle block event if needed
                redisService.consumerCommands.xack(streamKey, consumerGroup, messageId)
            }
        }
    }
}


