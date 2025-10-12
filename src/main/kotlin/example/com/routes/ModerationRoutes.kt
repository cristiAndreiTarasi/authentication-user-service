package example.com.routes

import example.com.LiveEventJson
import example.com.ModerationReason
import example.com.ModerationSeverity
import example.com.StreamStatus
import example.com.routes.dtos.LiveEvent
import example.com.routes.dtos.withDefaults
import example.com.schemas.StreamSchema
import example.com.services.redis.RedisManager
import example.com.services.ws_session.SessionManager
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.websocket.Frame
import kotlinx.serialization.PolymorphicSerializer
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
    // Resolve a canonical room id (prefer streamKey). Accept either a streamKey or numeric streamId in the path.
    suspend fun resolveRoomIdParam(param: String): String {
        // if param looks like a UUID/string streamKey -> return as-is
        // otherwise try parse as integer id and fetch stream by id to obtain stream_key
        val numeric = param.toIntOrNull()
        return if (numeric == null) {
            param
        } else {
            // find stream by id and return its streamKey (if exists) otherwise fallback to numeric id as string
            val s = streamSchema.findById(numeric)
            s?.streamKey ?: param
        }
    }


    route("/internal/moderation") {
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

            val rawParam = call.parameters["streamId"] ?: run {
                call.respond(HttpStatusCode.BadRequest, ModerationActionResponse(false, "Missing streamId", ""))
                return@post
            }

            try {
                val request = call.receive<ModerationWarningRequest>()
                val message = request.message ?: ModerationMessages.getWarningMessage(request.severity, request.reason)

                // Resolve canonical room id (prefer streamKey)
                val roomId = resolveRoomIdParam(rawParam)

                val warningEvent = LiveEvent.ModerationWarningEvent(
                    roomId = roomId,
                    severity = request.severity.name.lowercase(),
                    reason = request.reason.name.lowercase(),
                    message = message
                )

                // Add to Redis stream (consumer will broadcast to sessions)
                redisManager.addToStream(warningEvent)

                // optionally: immediate direct send to sessions (not required if your consumer broadcasts fast)
                // val safe = warningEvent.withDefaults()
                // val json = LiveEventJson.encodeToString(PolymorphicSerializer(LiveEvent::class), safe)
                // SessionManager.getRoomSessions(roomId).forEach { session -> ... }

                call.respond(HttpStatusCode.OK, ModerationActionResponse(
                    success = true,
                    message = "Warning sent successfully",
                    streamId = rawParam
                ))

                application.log.info("Moderation warning sent for stream $rawParam -> roomId=$roomId: ${request.severity} - ${request.reason}")

            } catch (e: Exception) {
                application.log.error("Failed to send moderation warning for stream $rawParam", e)
                call.respond(HttpStatusCode.InternalServerError, ModerationActionResponse(
                    false, "Failed to send warning: ${e.message}", rawParam
                ))
            }
        }

        post("/streams/{streamId}/terminate") {
            if (!authenticateModerationRequest(call)) {
                call.respond(HttpStatusCode.Unauthorized, ModerationActionResponse(false, "Invalid moderation secret", ""))
                return@post
            }

            val rawParam = call.parameters["streamId"] ?: run {
                call.respond(HttpStatusCode.BadRequest, ModerationActionResponse(false, "Missing streamId", ""))
                return@post
            }

            try {
                val request = call.receive<StreamTerminationRequest>()
                val message = request.message ?: ModerationMessages.getTerminationMessage(request.reason)

                // Resolve canonical roomId (prefer streamKey)
                val roomId = resolveRoomIdParam(rawParam)

                val terminationEvent = LiveEvent.StreamTerminatedEvent(
                    roomId = roomId,
                    reason = request.reason.name.lowercase(),
                    message = message
                )

                // Add to Redis stream (consumer will broadcast)
                redisManager.addToStream(terminationEvent)

                // Update DB: mark terminated. If caller passed numeric id use it, otherwise look up stream by streamKey
                val numeric = rawParam.toIntOrNull()
                val marked = if (numeric != null) {
                    streamSchema.markTerminated(numeric.toString(), "moderation_${request.reason.name.lowercase()}")
                } else {
                    // try to find by streamKey
                    val stream = streamSchema.findByStreamKey(rawParam)
                    if (stream != null) {
                        streamSchema.markTerminated(stream.id.toString(), "moderation_${request.reason.name.lowercase()}")
                    } else {
                        false
                    }
                }

                call.respond(HttpStatusCode.OK, ModerationActionResponse(
                    success = true,
                    message = "Stream terminated successfully",
                    streamId = rawParam
                ))

                application.log.info("Stream $rawParam terminated via moderation -> roomId=$roomId: ${request.reason}")

            } catch (e: Exception) {
                application.log.error("Failed to terminate stream $rawParam", e)
                call.respond(HttpStatusCode.InternalServerError, ModerationActionResponse(
                    false, "Failed to terminate stream: ${e.message}", rawParam
                ))
            }
        }

        get("/streams/{streamId}/status") {
            if (!authenticateModerationRequest(call)) {
                call.respond(HttpStatusCode.Unauthorized, "Invalid moderation secret")
                return@get
            }

            val rawParam = call.parameters["streamId"] ?: run {
                call.respond(HttpStatusCode.BadRequest, "Missing streamId")
                return@get
            }

            try {
                // Resolve canonical room id (prefer streamKey)
                val roomId = resolveRoomIdParam(rawParam)

                // Try to look up canonical stream (by streamKey if possible)
                val stream = streamSchema.findByStreamKey(roomId) ?: run {
                    // maybe rawParam was numeric id, then we already used resolveRoomIdParam to return streamKey fallback;
                    // if still null, try parse numeric and find by id
                    rawParam.toIntOrNull()?.let { streamSchema.findById(it) }
                }

                if (stream == null) {
                    call.respond(HttpStatusCode.NotFound, "Stream not found")
                    return@get
                }

                val sessionCount = SessionManager.getRoomSessions(roomId).size

                call.respond(StreamStatusResponse(
                    streamId = roomId,
                    status = stream.status.dbValue,
                    sessionCount = sessionCount,
                    isLive = stream.status == StreamStatus.PUBLISHING,
                    terminated = stream.status == StreamStatus.TERMINATED
                ))
            } catch (e: Exception) {
                application.log.error("Failed to get stream status for $rawParam", e)
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