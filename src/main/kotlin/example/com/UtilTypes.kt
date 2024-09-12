package example.com

enum class PrivacyOptions(val displayName: String) {
    PUBLIC("Public"),
    PRIVATE("Private"),
    KEY("Key")
}

enum class UserRole(val roleName: String) {
    OWNER("owner"),
    ADMIN("admin"),
    MODERATOR("moderator"),
    VIP("vip"),
    USER("user"),
    GUEST("guest");
}

enum class PartDataItems(val displayName: String) {
    METADATA("metadata"),
    THUMBNAIL("thumbnail")
}