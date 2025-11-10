package example.com.config

import example.com.routes.dtos.LiveEvent
import example.com.routes.dtos.NotificationEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.*

/**
 * JSON configuration for serializing/deserializing LiveEvent objects.
 * Uses polymorphic serialization to handle different types of live streaming events.
 */
val LiveEventJson = SerializersModule {
    polymorphic(LiveEvent::class) {
        // Register ALL LiveEvent subclasses for polymorphic serialization
        subclass(LiveEvent.JoinRoom::class)
        subclass(LiveEvent.LeaveRoom::class)
        subclass(LiveEvent.ChatMessage::class)
        subclass(LiveEvent.Like::class)
        subclass(LiveEvent.Gift::class)
        subclass(LiveEvent.KickUser::class)
        subclass(LiveEvent.MuteUser::class)
        subclass(LiveEvent.UnmuteUser::class)
        subclass(LiveEvent.GrantModerator::class)
        subclass(LiveEvent.RevokeModerator::class)
        subclass(LiveEvent.StreamStats::class)
        subclass(LiveEvent.PublisherInfoEvent::class)
        subclass(LiveEvent.StreamEndedEvent::class)
        subclass(LiveEvent.SystemMessage::class)
        subclass(LiveEvent.ModerationWarningEvent::class)
        subclass(LiveEvent.StreamTerminatedEvent::class)
        subclass(LiveEvent.ModerationClearEvent::class)
        subclass(LiveEvent.ModerationAck::class)
    }
}

/**
 * JSON configuration for serializing/deserializing NotificationEvent objects.
 * Handles different types of user notification events.
 */
val NotificationEventJson = SerializersModule {
    polymorphic(NotificationEvent::class) {
        subclass(NotificationEvent.Follow::class)
        subclass(NotificationEvent.InitialState::class)
        subclass(NotificationEvent.MarkRead::class)
        subclass(NotificationEvent.ProfileUpdate::class)
        subclass(NotificationEvent.UserIsLive::class)
    }
}

private fun createAppJson(): Json {
    // Combine modules so a single Json knows how to handle both polymorphic hierarchies
    val combinedModule = LiveEventJson + NotificationEventJson

    return Json {
        ignoreUnknownKeys = true // Ignore unknown fields for forward compatibility
        classDiscriminator = "type" // Use "type" field to distinguish between event types
        encodeDefaults = true
        prettyPrint = true
        isLenient = true
        serializersModule = combinedModule
    }
}

/**
 * Convenience single instance you can import/use across the app.
 * For tests / multi-instance boot you may prefer to call createAppJson() explicitly.
 */
val AppJson: Json = createAppJson()