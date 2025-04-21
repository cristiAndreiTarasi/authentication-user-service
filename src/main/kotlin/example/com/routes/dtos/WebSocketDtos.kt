package example.com.routes.dtos

import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class BroadcastEvent {
    abstract val type: String
}

@Serializable
@SerialName("chat_message_in")
data class ChatMessageIn(
    override val type: String = "chat_message",
    val userId:   String,
    val username: String,
    val message:  String
) : BroadcastEvent()

// this is what we send _to_ every client
@Serializable
@SerialName("chat_message")
data class ChatMessageOut(
    override val type: String = "chat_message",
    val userId:    String,
    val username:  String,
    val message:   String
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

@Serializable
@SerialName("publisher_info")
data class PublisherInfo(
    override val type: String = "publisher_info",
    val userId: String
) : BroadcastEvent()

@Serializable
@SerialName("publisher_disconnected")
data class PublisherDisconnected(
    override val type: String = "publisher_disconnected"
) : BroadcastEvent()

@Serializable
@SerialName("user_joined")
data class UserJoined(
    @Required override val type: String = "user_joined",
    val userId: String,
    val username: String
) : BroadcastEvent()


