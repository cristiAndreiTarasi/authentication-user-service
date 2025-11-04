package example.com.routes.dtos

import example.com.ProfileUpdateType
import example.com.SocialEventType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Data transfer object representing a notification in the database.
 * Maps to the notifications table structure.
 */
@Serializable
data class NotificationDto(
    val id: Int,              // Unique notification ID
    val userId: Int,          // User who receives the notification
    val actorId: Int?,        // User who triggered the notification (e.g., follower)
    val type: String,         // Notification type (e.g., "follow", "user_is_live")
    val text: String?,        // Notification message text
    val isRead: Boolean,      // Whether the notification has been read
    val meta: String?,        // JSON string with additional metadata
    val createdAt: String     // Creation timestamp
)

/**
 * Sealed class representing different types of real-time notification events
 * that can be sent over WebSocket connections.
 */
@Serializable
sealed class NotificationEvent {
    abstract val userId: String      // Target user ID for the notification
    abstract val timestamp: Long?    // Event timestamp (optional)

    /** Event when a user follows another user */
    @Serializable
    @SerialName("Follow")
    data class Follow(
        override val userId: String,
        override val timestamp: Long? = null,
        val actorId: String,           // User who performed the follow
        val actorUsername: String?,    // Username of the actor
        val actorAvatarUrl: String?,   // Avatar URL of the actor
        val text: String               // Notification text
    ) : NotificationEvent()

    /** Event when a followed user starts a live stream */
    @Serializable
    @SerialName("UserIsLive")
    data class UserIsLive(
        override val userId: String,
        override val timestamp: Long? = null,
        val actorId: String,           // User who started streaming
        val actorUsername: String?,    // Streamer's username
        val actorAvatarUrl: String?,   // Streamer's avatar URL
        val text: String               // Notification text
    ) : NotificationEvent()

    /** Initial state event sent when a user connects to notifications WebSocket */
    @Serializable
    @SerialName("InitialState")
    data class InitialState(
        override val userId: String,
        override val timestamp: Long? = null,
        val unreadCount: Int,              // Number of unread notifications
        val notifications: List<NotificationDto>  // Recent notifications
    ) : NotificationEvent()

    /** Event to mark a notification as read */
    @Serializable
    @SerialName("MarkRead")
    data class MarkRead(
        override val userId: String,
        override val timestamp: Long? = null,
        val notificationId: Int        // ID of notification to mark as read
    ) : NotificationEvent()

    /** Event for profile updates (followers count, following count, etc.) */
    @Serializable
    @SerialName("ProfileUpdate")
    data class ProfileUpdate(
        override val userId: String,
        override val timestamp: Long? = null,
        val updateType: ProfileUpdateType, // Type of update: "followers", "following", "likes"
        val count: Int,                   // New count value
        val userIds: List<Int>? = null    // List of user IDs (for followers/following)
    ) : NotificationEvent()
}

fun NotificationEvent.withDefaults(): NotificationEvent {
    val now = System.currentTimeMillis()
    return when (this) {
        is NotificationEvent.Follow -> this.copy(timestamp = this.timestamp ?: now)
        is NotificationEvent.UserIsLive -> this.copy(timestamp = this.timestamp ?: now)
        is NotificationEvent.InitialState -> this.copy(timestamp = this.timestamp ?: now)
        is NotificationEvent.MarkRead -> this.copy(timestamp = this.timestamp ?: now)
        is NotificationEvent.ProfileUpdate -> this.copy(timestamp = this.timestamp ?: now)
    }
}
