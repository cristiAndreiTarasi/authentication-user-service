package example.com.routes.dtos

import io.ktor.server.websocket.DefaultWebSocketServerSession
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ─── 1) Incoming Commands ───────────────────────────────────────────────────
@Serializable
sealed class WsCommand {
    abstract val type: String
}

@Serializable @SerialName("chat_message")
data class ChatMessageCommand(
    override val type: String = "chat_message",
    val userId: String,
    val username: String,
    val message: String
) : WsCommand()

@Serializable @SerialName("like")
object LikeCommand : WsCommand() {
    override val type: String = "like"
}

@Serializable @SerialName("mute_user")
data class MuteUserCommand(
    override val type: String = "mute_user",
    val targetUserId: String,
    val durationMs: Long
) : WsCommand()

@Serializable @SerialName("unmute_user")
data class UnmuteUserCommand(
    override val type: String = "unmute_user",
    val targetUserId: String
) : WsCommand()

@Serializable @SerialName("kick_user")
data class KickUserCommand(
    override val type: String = "kick_user",
    val targetUserId: String
) : WsCommand()

@Serializable @SerialName("grant_moderator")
data class GrantModeratorCommand(
    override val type: String = "grant_moderator",
    val targetUserId: String
) : WsCommand()

@Serializable @SerialName("revoke_moderator")
data class RevokeModeratorCommand(
    override val type: String = "revoke_moderator",
    val targetUserId: String
) : WsCommand()

// ─── 2) Outgoing Events ────────────────────────────────────────────────────
@Serializable
sealed class WsEvent {
    abstract val type: String
}

@Serializable @SerialName("chat_message")
data class ChatMessageEvent(
    override val type: String = "chat_message",
    val userId: String,
    val username: String,
    val message: String
) : WsEvent()

@Serializable @SerialName("like_update")
data class LikeUpdateEvent(
    override val type: String = "like_update",
    val newCount: Int
) : WsEvent()

@Serializable @SerialName("visitor_count_update")
data class VisitorCountEvent(
    override val type: String = "visitor_count_update",
    val currentCount: Int
) : WsEvent()

@Serializable @SerialName("publisher_info")
data class PublisherInfoEvent(
    override val type: String = "publisher_info",
    val userId: String
) : WsEvent()

@Serializable @SerialName("publisher_disconnected")
object PublisherDisconnectedEvent : WsEvent() {
    override val type: String = "publisher_disconnected"
}

@Serializable @SerialName("user_joined")
data class UserJoinedEvent(
    override val type: String = "user_joined",
    val userId: String,
    val username: String
) : WsEvent()

@Serializable @SerialName("user_muted")
data class UserMutedEvent(
    override val type: String = "user_muted",
    val targetUserId: String,
    val durationMs: Long
) : WsEvent()

@Serializable @SerialName("user_unmuted")
data class UserUnmutedEvent(
    override val type: String = "user_unmuted",
    val targetUserId: String
) : WsEvent()

@Serializable @SerialName("user_kicked")
data class UserKickedEvent(
    override val type: String = "user_kicked",
    val targetUserId: String,
    val reason: String? = null
) : WsEvent()

@Serializable @SerialName("moderator_granted")
data class ModeratorGrantedEvent(
    override val type: String = "moderator_granted",
    val targetUserId: String
) : WsEvent()

@Serializable @SerialName("moderator_revoked")
data class ModeratorRevokedEvent(
    override val type: String = "moderator_revoked",
    val targetUserId: String
) : WsEvent()

@Serializable @SerialName("moderator_action_success")
data class ModeratorActionSuccessEvent(
    override val type: String = "moderator_action_success",
    val action: String,            // "grant","revoke","mute","unmute","kick"
    val targetUserId: String,
    val targetUsername: String,
    val durationMs: Long? = null
) : WsEvent()

@Serializable @SerialName("moderator_action_failure")
data class ModeratorActionFailureEvent(
    override val type: String = "moderator_action_failure",
    val action: String,            // "grant","revoke","mute","unmute","kick"
    val targetUserId: String,
    val targetUsername: String,
    val reason: String
) : WsEvent()

data class UserSession(
    val wsSession: DefaultWebSocketServerSession,
    val userId: String
)

