package example.com.routes

import example.com.ModerationReason
import example.com.ModerationSeverity
import example.com.StreamStatus
import example.com.routes.dtos.LiveEvent
import example.com.schemas.StreamSchema
import example.com.services.redis.RedisManager
import example.com.services.ws_session.SessionManager
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class ModerationWarningRequest(
    val severity: ModerationSeverity,
    val reason: ModerationReason,
    val message: String? = null
)

@Serializable
data class StreamTerminationRequest(
    val reason: ModerationReason,
    val message: String? = null
)

@Serializable
data class ModerationActionResponse(
    val success: Boolean,
    val message: String,
    val streamId: String
)

@Serializable
data class StreamStatusResponse(
    val streamId: String,
    val status: String,
    val sessionCount: Int,
    val isLive: Boolean,
    val terminated: Boolean
)

fun Route.moderationRoutes(redisManager: RedisManager, streamSchema: StreamSchema) {
    route("/internal/moderation") {
        // Authentication middleware for internal services
        val moderationSecret = System.getenv("MODERATION_SECRET") ?: "default_moderation_secret"

        fun authenticateModerationRequest(call: ApplicationCall): Boolean {
            val providedSecret = call.request.headers["X-Moderation-Secret"]
            return providedSecret == moderationSecret
        }

        post("/streams/{streamId}/warning") {
            if (!authenticateModerationRequest(call)) {
                call.respond(HttpStatusCode.Unauthorized, ModerationActionResponse(false, "Invalid moderation secret", ""))
                return@post
            }

            val streamId = call.parameters["streamId"] ?: run {
                call.respond(HttpStatusCode.BadRequest, ModerationActionResponse(false, "Missing streamId", ""))
                return@post
            }

            try {
                val request = call.receive<ModerationWarningRequest>()

                // Use message templates based on severity and reason
                val message = request.message ?: ModerationMessages.getWarningMessage(request.severity, request.reason)

                val warningEvent = LiveEvent.ModerationWarningEvent(
                    roomId = streamId,
                    severity = request.severity.name.toLowerCase(),
                    reason = request.reason.name.toLowerCase(),
                    message = message
                )

                // Add to Redis stream for broadcasting
                redisManager.addToStream(warningEvent)

                call.respond(HttpStatusCode.OK, ModerationActionResponse(
                    success = true,
                    message = "Warning sent successfully",
                    streamId = streamId
                ))

                // Log the moderation action
                application.log.info("Moderation warning sent for stream $streamId: ${request.severity} - ${request.reason}")

            } catch (e: Exception) {
                application.log.error("Failed to send moderation warning for stream $streamId", e)
                call.respond(HttpStatusCode.InternalServerError, ModerationActionResponse(
                    false, "Failed to send warning: ${e.message}", streamId
                ))
            }
        }

        post("/streams/{streamId}/terminate") {
            if (!authenticateModerationRequest(call)) {
                call.respond(HttpStatusCode.Unauthorized, ModerationActionResponse(false, "Invalid moderation secret", ""))
                return@post
            }

            val streamId = call.parameters["streamId"] ?: run {
                call.respond(HttpStatusCode.BadRequest, ModerationActionResponse(false, "Missing streamId", ""))
                return@post
            }

            try {
                val request = call.receive<StreamTerminationRequest>()

                val message = request.message ?: ModerationMessages.getTerminationMessage(request.reason)

                val terminationEvent = LiveEvent.StreamTerminatedEvent(
                    roomId = streamId,
                    reason = request.reason.name.toLowerCase(),
                    message = message
                )

                // Add termination event to Redis stream
                redisManager.addToStream(terminationEvent)

                // Update stream status in database
                streamSchema.markTerminated(streamId, "moderation_${request.reason.name.toLowerCase()}")

                call.respond(HttpStatusCode.OK, ModerationActionResponse(
                    success = true,
                    message = "Stream terminated successfully",
                    streamId = streamId
                ))

                application.log.info("Stream $streamId terminated via moderation: ${request.reason}")

            } catch (e: Exception) {
                application.log.error("Failed to terminate stream $streamId", e)
                call.respond(HttpStatusCode.InternalServerError, ModerationActionResponse(
                    false, "Failed to terminate stream: ${e.message}", streamId
                ))
            }
        }

        get("/streams/{streamId}/status") {
            if (!authenticateModerationRequest(call)) {
                call.respond(HttpStatusCode.Unauthorized, "Invalid moderation secret")
                return@get
            }

            val streamId = call.parameters["streamId"] ?: run {
                call.respond(HttpStatusCode.BadRequest, "Missing streamId")
                return@get
            }

            try {
                val stream = streamSchema.findByStreamKey(streamId)
                if (stream == null) {
                    call.respond(HttpStatusCode.NotFound, "Stream not found")
                    return@get
                }

                // Get current WebSocket session count
                val sessionCount = SessionManager.getRoomSessions(streamId).size

                // FIX: Use dbValue for status string and proper enum comparisons
                call.respond(StreamStatusResponse(
                    streamId = streamId,
                    status = stream.status.dbValue, // Use the string value from enum
                    sessionCount = sessionCount,
                    isLive = stream.status == StreamStatus.PUBLISHING, // Compare enums directly
                    terminated = stream.status == StreamStatus.TERMINATED // Compare enums directly
                ))

            } catch (e: Exception) {
                application.log.error("Failed to get stream status for $streamId", e)
                call.respond(HttpStatusCode.InternalServerError, "Failed to get stream status")
            }
        }
    }
}

// Message templates for different moderation scenarios
object ModerationMessages {
    fun getWarningMessage(severity: ModerationSeverity, reason: ModerationReason): String {
        return when (severity) {
            ModerationSeverity.WARNING -> when (reason) {
                ModerationReason.SEXUAL_CONTENT -> "Inappropriate content detected. Please adjust your stream content."
                ModerationReason.VIOLENT_CONTENT -> "Violent content detected. Please adjust your stream content."
                ModerationReason.MANUAL -> "Content policy violation detected. Please review community guidelines."
                ModerationReason.OTHER -> "Content policy violation detected. Please review community guidelines."
            }
            ModerationSeverity.BLOCKED -> when (reason) {
                ModerationReason.SEXUAL_CONTENT -> "Stream temporarily blocked due to sexual content violations."
                ModerationReason.VIOLENT_CONTENT -> "Stream temporarily blocked due to violent content violations."
                ModerationReason.MANUAL -> "Stream temporarily blocked due to content policy violations."
                ModerationReason.OTHER -> "Stream temporarily blocked due to content policy violations."
            }
            ModerationSeverity.TERMINATED -> when (reason) {
                ModerationReason.SEXUAL_CONTENT -> "Stream terminated for repeated sexual content violations."
                ModerationReason.VIOLENT_CONTENT -> "Stream terminated for repeated violent content violations."
                ModerationReason.MANUAL -> "Stream terminated by moderator."
                ModerationReason.OTHER -> "Stream terminated for repeated content policy violations."
            }
        }
    }

    fun getTerminationMessage(reason: ModerationReason): String {
        return when (reason) {
            ModerationReason.SEXUAL_CONTENT -> "Stream terminated due to repeated sexual content violations."
            ModerationReason.VIOLENT_CONTENT -> "Stream terminated due to repeated violent content violations."
            ModerationReason.MANUAL -> "Stream terminated by moderator."
            ModerationReason.OTHER -> "Stream terminated due to content policy violations."
        }
    }
}