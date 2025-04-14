package example.com.routes.dtos

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class BroadcastEvent {
    abstract val type: String

    @Serializable
    @SerialName("chat_message")
    data class ChatMessage(
        override val type: String = "chat_message",
        val userId: String,
        val message: String
    ) : BroadcastEvent()

    @Serializable
    @SerialName("like_update")
    data class LikeUpdate(
        override val type: String = "like_update",
        val newCount: Int
    ) : BroadcastEvent()

    @Serializable
    @SerialName("visitor_count_update")
    data class VisitorCountUpdate(
        override val type: String = "visitor_count_update",
        val currentCount: Int
    ) : BroadcastEvent()
}

