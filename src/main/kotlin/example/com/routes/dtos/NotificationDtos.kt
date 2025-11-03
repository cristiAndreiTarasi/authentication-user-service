package example.com.routes.dtos

import example.com.ProfileUpdateType
import example.com.SocialEventType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class NotificationDto(
    val id: Int,
    val userId: Int,
    val actorId: Int?,
    val type: String,
    val text: String?,
    val isRead: Boolean,
    val meta: String?, // JSON string
    val createdAt: String
)

@Serializable
sealed class NotificationEvent {
    abstract val userId: String
    abstract val timestamp: Long?

    @Serializable
    @SerialName("Follow")
    data class Follow(
        override val userId: String,
        override val timestamp: Long? = null,
        val actorId: String,
        val actorUsername: String?,
        val actorAvatarUrl: String?,
        val text: String
    ) : NotificationEvent()

    @Serializable
    @SerialName("UserIsLive")
    data class UserIsLive(
        override val userId: String,
        override val timestamp: Long? = null,
        val actorId: String,
        val actorUsername: String?,
        val actorAvatarUrl: String?,
        val text: String
    ) : NotificationEvent()

    @Serializable
    @SerialName("InitialState")
    data class InitialState(
        override val userId: String,
        override val timestamp: Long? = null,
        val unreadCount: Int,
        val notifications: List<NotificationDto>
    ) : NotificationEvent()

    @Serializable
    @SerialName("MarkRead")
    data class MarkRead(
        override val userId: String,
        override val timestamp: Long? = null,
        val notificationId: Int
    ) : NotificationEvent()

    @Serializable
    @SerialName("ProfileUpdate")
    data class ProfileUpdate(
        override val userId: String,
        override val timestamp: Long? = null,
        val updateType: ProfileUpdateType, // "followers", "following", "likes"
        val count: Int,
        val userIds: List<Int>? = null
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
