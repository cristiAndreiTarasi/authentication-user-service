package example.com.services.notifications

import example.com.NotificationEventJson
import example.com.ProfileUpdateType
import example.com.SocialEventType
import example.com.routes.dtos.NotificationEvent
import example.com.routes.dtos.withDefaults
import example.com.schemas.NotificationSchema
import example.com.schemas.UserSchema
import example.com.services.redis.SocialEvent
import example.com.services.redis.RedisService
import io.lettuce.core.Consumer
import io.lettuce.core.RedisCommandExecutionException
import io.lettuce.core.StreamMessage
import io.lettuce.core.XGroupCreateArgs
import io.lettuce.core.XReadArgs
import io.lettuce.core.RedisException
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class NotificationWorker(
    private val redisService: RedisService,
    private val notificationSchema: NotificationSchema,
    private val userSchema: UserSchema,
    private val consumerGroup: String = "notifications-group",
    private val consumerId: String = "notif-consumer-${System.getenv("HOSTNAME") ?: "local"}",
    private val streamKey: String = "social_events"
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun run() {
        println("DEBUG: NotificationWorker starting...")

        // Create consumer group if not exists
        try {
            redisService.createConsumerGroupIfNotExists(streamKey, consumerGroup)
        } catch (e: Exception) {
            println("DEBUG: Error creating consumer group: ${e.message}")
        }

        while (true) {
            try {
                val messages: List<StreamMessage<String, String>> =
                    redisService.consumerCommands.xreadgroup(
                        Consumer.from(consumerGroup, consumerId),
                        XReadArgs.Builder.block(5000).count(100),
                        XReadArgs.StreamOffset.from(streamKey, ">")
                    )

                if (messages.isEmpty()) {
                    delay(100)
                    continue
                }

                println("DEBUG: NotificationWorker processing ${messages.size} messages")

                for (msg in messages) {
                    val raw = msg.body["event"]
                    if (raw == null) {
                        redisService.consumerCommands.xack(streamKey, consumerGroup, msg.id)
                        continue
                    }

                    try {
                        val socialEvent = json.decodeFromString(SocialEvent.serializer(), raw)
                        handleSocialEvent(socialEvent, msg.id)
                    } catch (e: Exception) {
                        println("DEBUG: Error processing message ${msg.id}: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                println("DEBUG: NotificationWorker error: ${e.message}")
                delay(1000)
            }
        }
    }

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

        // 1. Send Follow NotificationEvent via WebSocket
        val followEvent = NotificationEvent.Follow(
            userId = targetIdInt.toString(),
            actorId = event.actorId,
            actorUsername = actorUsername,
            text = storedText
        ).withDefaults()

        val eventJson = NotificationEventJson.encodeToString(followEvent)
        val delivered = NotificationSessionRegistry.sendToUser(targetIdInt, eventJson)
        println("DEBUG: WebSocket Follow event delivery to user $targetIdInt: $delivered")

        // 2. Store in database (for offline users)
        if (!delivered) {
            val stored = notificationSchema.insertNotification(
                userId = targetIdInt,
                actorId = actorIdInt,
                type = "follow",
                text = storedText,
                meta = mapOf(
                    "actorUsername" to (event.actorUsername ?: "")
                )
            )
            println("DEBUG: Database storage for user $targetIdInt: $stored")
        }

        // 3. Send ProfileUpdate events using enum
        sendProfileUpdate(targetIdInt, ProfileUpdateType.FOLLOWERS)
        sendProfileUpdate(actorIdInt, ProfileUpdateType.FOLLOWING)

        redisService.consumerCommands.xack(streamKey, consumerGroup, messageId)
        println("DEBUG: Processed follow event for target user $targetIdInt")
    }

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

    private suspend fun sendProfileUpdate(userId: Int, updateType: ProfileUpdateType) {
        // Get current count from database using type-safe when
        val count = when (updateType) {
            ProfileUpdateType.FOLLOWERS -> userSchema.getUserFollowers(userId).tally
            ProfileUpdateType.FOLLOWING -> userSchema.getUserFollowing(userId).tally
            ProfileUpdateType.UNREAD_COUNT -> notificationSchema.getUnreadCount(userId)
        }

        val profileEvent = NotificationEvent.ProfileUpdate(
            userId = userId.toString(),
            updateType = updateType.name, // Use enum name for consistency
            count = count
        ).withDefaults()

        val eventJson = NotificationEventJson.encodeToString(profileEvent)
        val sent = NotificationSessionRegistry.sendToUser(userId, eventJson)
        if (sent) {
            println("DEBUG: Sent profile update to user $userId: $updateType = $count")
        }
    }

    private suspend fun handleSocialEvent(event: SocialEvent, messageId: String) {
        val actorIdInt = event.actorId.toIntOrNull()
        val targetIdInt = event.targetId.toIntOrNull()

        if (actorIdInt == null || targetIdInt == null) {
            println("DEBUG: Invalid IDs in social event")
            redisService.consumerCommands.xack(streamKey, consumerGroup, messageId)
            return
        }

        when (event.type) {
            SocialEventType.FOLLOW -> handleFollowEvent(event, actorIdInt, targetIdInt, messageId)
            SocialEventType.UNFOLLOW -> handleUnfollowEvent(event, actorIdInt, targetIdInt, messageId)
            SocialEventType.BLOCK -> {}
        }

        redisService.consumerCommands.xack(streamKey, consumerGroup, messageId)
    }
}


