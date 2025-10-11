package example.com.routes.dtos

import io.ktor.server.websocket.DefaultWebSocketServerSession
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
sealed class LiveEvent {
    abstract val roomId: String
    abstract val initiatorId: String?
    abstract val timestamp: Long?

    @Serializable
    @SerialName("JoinRoom")
    data class JoinRoom(
        override val roomId: String,
        override val initiatorId: String = "system",
        override val timestamp: Long? = null,
        val username: String
    ) : LiveEvent()

    @Serializable
    @SerialName("LeaveRoom")
    data class LeaveRoom(
        override val roomId: String,
        override val initiatorId: String = "system",
        override val timestamp: Long? = null,
        val username: String
    ) : LiveEvent()

    @Serializable
    @SerialName("ChatMessage")
    data class ChatMessage(
        override val roomId: String,
        override val initiatorId: String? = null,
        override val timestamp: Long? = null,
        val text: String,
        val username: String
    ) : LiveEvent()

    @Serializable
    @SerialName("SystemMessage")
    data class SystemMessage(
        override val roomId: String,
        override val initiatorId: String = "system",
        override val timestamp: Long? = null,
        val text: String
    ) : LiveEvent()

    @Serializable
    @SerialName("Like")
    data class Like(
        override val roomId: String,
        override val initiatorId: String?,
        override val timestamp: Long? = null,
        val count: Int = 1
    ) : LiveEvent()

    @Serializable
    @SerialName("Gift")
    data class Gift(
        override val roomId: String,
        override val initiatorId: String?,
        override val timestamp: Long? = null,
        val giftId: String,
        val quantity: Int,
        val value: Double
    ) : LiveEvent()

    @Serializable
    @SerialName("KickUser")
    data class KickUser(
        override val roomId: String,
        override val initiatorId: String?,
        override val timestamp: Long? = null,
        val targetUserId: String
    ) : LiveEvent()

    @Serializable
    @SerialName("MuteUser")
    data class MuteUser(
        override val roomId: String,
        override val initiatorId: String?,
        override val timestamp: Long? = null,
        val targetUserId: String
    ) : LiveEvent()

    @Serializable
    @SerialName("UnmuteUser")
    data class UnmuteUser(
        override val roomId: String,
        override val initiatorId: String?,
        override val timestamp: Long? = null,
        val targetUserId: String
    ) : LiveEvent()

    @Serializable
    @SerialName("GrantModerator")
    data class GrantModerator(
        override val roomId: String,
        override val initiatorId: String?,
        override val timestamp: Long? = null,
        val targetUserId: String
    ) : LiveEvent()

    @Serializable
    @SerialName("RevokeModerator")
    data class RevokeModerator(
        override val roomId: String,
        override val initiatorId: String?,
        override val timestamp: Long? = null,
        val targetUserId: String
    ) : LiveEvent()

    @Serializable
    @SerialName("StreamStats")
    data class StreamStats(
        override val roomId: String,
        override val initiatorId: String? = null,
        override val timestamp: Long? = null,
        val viewerCount: Int,
        val totalLikes: Long
    ) : LiveEvent()

    @Serializable
    @SerialName("PublisherInfoEvent")
    data class PublisherInfoEvent(
        override val roomId: String,
        override val initiatorId: String? = null,
        override val timestamp: Long? = null,
        val userId: String,
        val username: String,
        val avatarUrl: String? = null  // Ensure nullable
    ) : LiveEvent()

    @Serializable
    @SerialName("StreamEndedEvent")
    data class StreamEndedEvent(
        override val roomId: String,
        override val initiatorId: String? = null,
        override val timestamp: Long? = null
    ) : LiveEvent()

    @Serializable
    @SerialName("StreamTerminatedEvent")
    data class StreamTerminatedEvent(
        override val roomId: String,
        val reason: String,
        val message: String,
        override val timestamp: Long = System.currentTimeMillis(),
        override val initiatorId: String = "system"
    ) : LiveEvent()

    @Serializable
    @SerialName("ModerationWarningEvent")
    data class ModerationWarningEvent(
        override val roomId: String,
        val severity: String, // "warning" | "blocked" | "terminated"
        val reason: String, // "sexual_content" | "violent_content"
        val message: String,
        override val timestamp: Long = System.currentTimeMillis(),
        override val initiatorId: String = "system"
    ) : LiveEvent()
}

// guarantees initiatorId + timestamp even if a callsite forgets them
fun LiveEvent.withDefaults(): LiveEvent {
    val now = System.currentTimeMillis()
    return when (this) {
        is LiveEvent.JoinRoom -> this.copy(
                initiatorId = this.initiatorId ?: "system",
                timestamp = this.timestamp ?: now
            )
        is LiveEvent.LeaveRoom -> this.copy(
                initiatorId = this.initiatorId ?: "system",
                timestamp = this.timestamp ?: now
            )
        is LiveEvent.ChatMessage -> this.copy(
                initiatorId = this.initiatorId ?: "system",
                timestamp = this.timestamp ?: now
            )
        is LiveEvent.SystemMessage -> this.copy(
                initiatorId = this.initiatorId ?: "system",
                timestamp = this.timestamp ?: now
            )
        is LiveEvent.Like -> this.copy(
                initiatorId = this.initiatorId ?: "system",
                timestamp = this.timestamp ?: now
            )
        is LiveEvent.Gift -> this.copy(
                initiatorId = this.initiatorId ?: "system",
                timestamp = this.timestamp ?: now
            )
        is LiveEvent.KickUser -> this.copy(
                initiatorId = this.initiatorId ?: "system",
                timestamp = this.timestamp ?: now
            )
        is LiveEvent.MuteUser -> this.copy(
                initiatorId = this.initiatorId ?: "system",
                timestamp = this.timestamp ?: now
            )
        is LiveEvent.UnmuteUser -> this.copy(
                initiatorId = this.initiatorId ?: "system",
                timestamp = this.timestamp ?: now
            )
        is LiveEvent.GrantModerator -> this.copy(
                initiatorId = this.initiatorId ?: "system",
                timestamp = this.timestamp ?: now
            )
        is LiveEvent.RevokeModerator -> this.copy(
                initiatorId = this.initiatorId ?: "system",
                timestamp = this.timestamp ?: now
            )
        is LiveEvent.StreamStats -> this.copy(
                initiatorId = this.initiatorId ?: "system",
                timestamp = this.timestamp ?: now
            )
        is LiveEvent.PublisherInfoEvent -> this.copy(
                initiatorId = this.initiatorId ?: "system",
                timestamp = this.timestamp ?: now
            )
        is LiveEvent.StreamEndedEvent -> this.copy(
                initiatorId = this.initiatorId ?: "system",
                timestamp = this.timestamp ?: now
            )

        is LiveEvent.ModerationWarningEvent -> this.copy(
                initiatorId = this.initiatorId ?: "system",
                timestamp = this.timestamp ?: now
            )
        is LiveEvent.StreamTerminatedEvent -> this.copy(
            initiatorId = this.initiatorId ?: "system",
            timestamp = this.timestamp ?: now
        )
    }
}
