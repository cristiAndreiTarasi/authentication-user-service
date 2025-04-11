package example.com.routes.dtos

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SocketAction(
    val action: String,          // "chat", "like", "mute", "ban"
    val payload: String? = null, // for chat, this will be the message text
    val targetUserId: String? = null // for moderation commands (mute/ban)
)

@Serializable
data class ChatPayload(
    val userId: String,
    val message: String
)

@Serializable
data class PresenceUpdateDto(
    val userId: String,
//    val username: String? = null,
//    val avatarUrl: String? = null,
    val joined: Boolean,
//    val role: String? = null,
    val timestamp: Long
)


@Serializable
data class ControlMessage(
    val message: String // e.g. "muted by moderator"
)

@Serializable
data class LikeDto(
    val streamId: String,
    val userId: Int
)

@Serializable
data class MessageDto(
    val streamId: String,
    val userId: Int,
    val content: String,
    val sentAt: String // ISO format string
)

@Serializable
data class GiftDto(
    val streamId: String,
    val userId: Int
    // Leave out additional gift details for now.
)

@Serializable
data class VisitorUpdateDto(
    val visitorCount: Int,
    val visitorList: List<VisitorDto>
)

@Serializable
data class VisitorDto(
    val userId: String,
//    val name: String,
//    val avatarUrl: String?
)

@Serializable
sealed class StreamSocketEvent {
    @Serializable
    @SerialName("visitor_update")
    data class VisitorUpdate(val payload: VisitorUpdateDto) : StreamSocketEvent()

    @Serializable
    @SerialName("like")
    data class Like(val payload: LikeDto) : StreamSocketEvent()

    @Serializable
    @SerialName("message")
    data class Message(val payload: MessageDto) : StreamSocketEvent()

    // Add additional events as needed
}


