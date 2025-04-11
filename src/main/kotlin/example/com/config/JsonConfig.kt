package example.com.config

import example.com.routes.dtos.StreamSocketEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic

// JsonConfig.kt
val streamEventModule = SerializersModule {
    polymorphic(StreamSocketEvent::class) {
        subclass(StreamSocketEvent.VisitorUpdate::class, StreamSocketEvent.VisitorUpdate.serializer())
        subclass(StreamSocketEvent.Like::class, StreamSocketEvent.Like.serializer())
        subclass(StreamSocketEvent.Message::class, StreamSocketEvent.Message.serializer())
        // Register additional subclasses as needed.
    }
}

val jsonFormat: Json = Json {
    serializersModule = streamEventModule
    ignoreUnknownKeys = true
    encodeDefaults = true
}