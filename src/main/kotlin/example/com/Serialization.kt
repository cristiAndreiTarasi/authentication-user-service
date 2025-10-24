package example.com

import example.com.routes.dtos.LiveEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.*

val LiveEventJson = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "type"
    serializersModule = SerializersModule {
        polymorphic(LiveEvent::class) {
            // Register ALL LiveEvent subclasses
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
        }
    }
}