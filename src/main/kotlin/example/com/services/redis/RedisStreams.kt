package example.com.services.redis

import example.com.routes.dtos.LiveEvent

// RedisStreams.kt
object RedisStreams {
    // DURABLE STREAMS (Redis Streams) - Persistent, ordered events
    const val CHAT_EVENTS = "chat_events"              // Chat messages for moderation & history
    const val MODERATION_EVENTS = "moderation_events"  // Moderation actions & audit trail
    const val ANALYTICS_EVENTS = "analytics_events"    // Engagement metrics & analytics
    const val BILLING_EVENTS = "billing_events"        // Financial transactions (gifts)
    const val SOCIAL_EVENTS = "social_events"          // Follows, notifications, social actions

    // REAL-TIME CHANNELS (Pub/Sub) - Ephemeral, high-performance delivery
    const val ROOM_EVENTS_PREFIX = "room_events:"      // Real-time room events
    const val USER_NOTIFICATIONS_CHANNEL = "user_notifications" // Cross-instance user notifications

    // CONTROL STREAMS (System-level)
    const val CROSS_INSTANCE_CONTROL = "cross_instance_control" // Instance coordination

    // HISTORY & STATE (Redis Lists/Hashes) - Limited retention
    const val ROOM_HISTORY_PREFIX = "room_history:"    // Chat history per room
    const val ROOM_COUNTERS_PREFIX = "room_counters:"  // Likes, viewer counts
}

/**
 * Event classification for routing decisions
 */
enum class EventCategory {
    CHAT,           // Chat messages, system messages
    MODERATION,     // Kick, mute, ban, warnings
    ANALYTICS,      // Likes, joins, leaves, engagement
    BILLING,        // Gifts, financial transactions
    SOCIAL,         // Follows, notifications
    CONTROL,        // System control messages
    REAL_TIME_ONLY  // Ephemeral events (stats, presence)
}

/**
 * Determines the appropriate stream/category for a LiveEvent
 */
fun LiveEvent.getEventCategory(): EventCategory = when (this) {
    is LiveEvent.ChatMessage -> EventCategory.CHAT
    is LiveEvent.SystemMessage -> EventCategory.CHAT // System messages go with chat
    is LiveEvent.KickUser -> EventCategory.MODERATION
    is LiveEvent.MuteUser -> EventCategory.MODERATION
    is LiveEvent.UnmuteUser -> EventCategory.MODERATION
    is LiveEvent.GrantModerator -> EventCategory.MODERATION
    is LiveEvent.RevokeModerator -> EventCategory.MODERATION
    is LiveEvent.ModerationWarningEvent -> EventCategory.MODERATION
    is LiveEvent.StreamTerminatedEvent -> EventCategory.MODERATION
    is LiveEvent.ModerationClearEvent -> EventCategory.MODERATION
    is LiveEvent.Like -> EventCategory.ANALYTICS
    is LiveEvent.JoinRoom -> EventCategory.ANALYTICS
    is LiveEvent.LeaveRoom -> EventCategory.ANALYTICS
    is LiveEvent.Gift -> EventCategory.BILLING
    is LiveEvent.StreamStats -> EventCategory.REAL_TIME_ONLY
    is LiveEvent.PublisherInfoEvent -> EventCategory.REAL_TIME_ONLY
    is LiveEvent.StreamEndedEvent -> EventCategory.CONTROL
    is LiveEvent.ModerationAck -> EventCategory.REAL_TIME_ONLY
}