package example.com

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