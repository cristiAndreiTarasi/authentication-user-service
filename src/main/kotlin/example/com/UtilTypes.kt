package example.com

import io.ktor.http.ContentType.Application.Json
import kotlinx.serialization.json.Json

enum class PrivacyOptions(val displayName: String) {
    PUBLIC("Public"),
    PRIVATE("Private"),
    KEY("Key")
}

enum class UserRole(val roleName: String) {
    OWNER("owner"),
    PUBLISHER("publisher"),
    VISITOR("visitor"),
    ADMIN("admin"),
    MODERATOR("moderator"),
    VIP("vip"),
    USER("user"),
    GUEST("guest");
}

enum class StreamAction(val actionName: String) {
    CHAT("chat"),
    LIKE("like"),
    MUTE("mute"),
    BAN("ban")
}

enum class PartDataItems(val displayName: String) {
    METADATA("metadata"),
    THUMBNAIL("thumbnail")
}

val WS_JSON = Json {
    encodeDefaults    = true
    ignoreUnknownKeys = true
    classDiscriminator = "type"
}

val appJson = Json {
    prettyPrint = true
    isLenient = true
    ignoreUnknownKeys = true
}

enum class ProfileUpdateType {
    FOLLOWERS,
    FOLLOWING,
    LIKES,
    UNREAD_COUNT
}

enum class SocialEventType {
    FOLLOW,
    UNFOLLOW,
    BLOCK,
    LIVE_STARTED,
}

enum class StreamStatus(val dbValue: String) {
    CREATED("created"),      // Stream created, not yet publishing
    PUBLISHING("publishing"), // Stream is live and broadcasting
    ENDED("ended"),          // Streamer voluntarily ended the stream
    TERMINATED("terminated"); // Platform forcibly ended the stream

    companion object {
        fun fromDb(value: String?): StreamStatus {
            return when (value) {
                "publishing" -> PUBLISHING
                "ended" -> ENDED
                "terminated" -> TERMINATED
                else -> CREATED
            }
        }
    }
}

enum class ModerationSeverity {
    WARNING,      // Initial content violation
    BLOCKED,      // Stream temporarily blocked with placeholder
    TERMINATED    // Stream permanently terminated
}

enum class ModerationReason {
    SEXUAL_CONTENT,
    VIOLENT_CONTENT,
    MANUAL,        // Manual moderation action
    OTHER          // Generic content violation
}
