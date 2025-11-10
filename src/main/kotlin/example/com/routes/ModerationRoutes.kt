package example.com.routes

import example.com.config.AppJson
import example.com.ModerationMessages
import example.com.ModerationReason
import example.com.ModerationSeverity
import example.com.StreamStatus
import example.com.routes.dtos.LiveEvent
import example.com.routes.dtos.withDefaults
import example.com.schemas.StreamSchema
import example.com.services.redis.RedisService
import example.com.services.ws_session.DistributedPermissionManager
import example.com.services.ws_session.LiveRoomSessionRegistry
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.websocket.Frame
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ModerationWarningRequest(
    val severity: ModerationSeverity,
    val reason: ModerationReason,
    val message: String? = null,
    val terminateAt: Long? = null,
    val warningUntil: Long? = null,
    val blockUntil: Long? = null
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

fun Route.moderationRoutes(
    redisManager: RedisService,
    streamSchema: StreamSchema,
    moderationPublishSecret: String,
    distributedPermissionManager: DistributedPermissionManager
) {
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
        fun authenticateModerationRequest(call: ApplicationCall): Boolean {
            val providedSecret = call.request.headers["X-Moderation-Secret"]
            return providedSecret == moderationPublishSecret
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
                val defaultMessage = ModerationMessages.getWarningMessage(request.severity, request.reason)
                val nowMs = System.currentTimeMillis()

                val roomId = resolveRoomIdParam(rawParam)

                val warningEvent = LiveEvent.ModerationWarningEvent(
                    roomId = roomId,
                    severity = request.severity.name.lowercase(),
                    reason = request.reason.name.lowercase(),
                    message = defaultMessage,
                    terminateAt = request.terminateAt,
                    warningUntil = request.warningUntil,
                    blockUntil = request.blockUntil,
                    origin = "api-gateway",   // optional origin tag for dedupe
                    timestamp = nowMs
                )

                redisManager.addToModerationStream(warningEvent)

                // Immediate low-latency per-session dispatch using same timestamp
                val sessions = LiveRoomSessionRegistry.getRoomSessions(roomId).toList()
                val streamOwnerId = distributedPermissionManager.getStreamOwner(roomId)
                val liveEventPolymorphic = PolymorphicSerializer(LiveEvent::class)

                sessions.forEach { session ->
                    try {
                        val sessionUserId = LiveRoomSessionRegistry.getUserId(session)
                        val isStreamer = sessionUserId == streamOwnerId

                        // Choose final message text for streamer vs viewers
                        val finalMessage = if (isStreamer) {
                            ModerationMessages.getStreamerWarningMessage(request.severity, request.reason)
                        } else {
                            ModerationMessages.getWarningMessage(request.severity, request.reason)
                        }

                        val LiveEventJson: Json = AppJson
                        // send a copy of the same event but with user-specific message
                        val userSpecificEvent = warningEvent.copy(message = finalMessage)
                        val json = LiveEventJson.encodeToString(liveEventPolymorphic, userSpecificEvent.withDefaults())
                        session.send(Frame.Text(json))
                    } catch (e: Exception) {
                        try { LiveRoomSessionRegistry.removeSession(session) } catch (_: Exception) {}
                    }
                }

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

        post("/streams/{streamId}/clear") {
            if (!authenticateModerationRequest(call)) {
                call.respond(HttpStatusCode.Unauthorized, ModerationActionResponse(false, "Invalid moderation secret", ""))
                return@post
            }

            val rawParam = call.parameters["streamId"] ?: run {
                call.respond(HttpStatusCode.BadRequest, ModerationActionResponse(false, "Missing streamId", ""))
                return@post
            }

            try {
                val roomId = resolveRoomIdParam(rawParam)
                val clearEvent = LiveEvent.ModerationClearEvent(roomId = roomId)

                redisManager.addToModerationStream(clearEvent)

                call.respond(HttpStatusCode.OK, ModerationActionResponse(
                    success = true,
                    message = "Moderation clear sent successfully",
                    streamId = rawParam
                ))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ModerationActionResponse(
                    false, "Failed to send clear: ${e.message}", rawParam
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
                val message = ModerationMessages.getTerminationMessage(request.reason)

                val roomId = resolveRoomIdParam(rawParam)

                val terminationEvent = LiveEvent.StreamTerminatedEvent(
                    roomId = roomId,
                    reason = request.reason.name.lowercase(),
                    message = message
                )

                redisManager.addToModerationStream(terminationEvent)

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

                val sessionCount = LiveRoomSessionRegistry.getRoomSessions(roomId).size
                val metadata = redisManager.getStreamMetadata(roomId)
                val isAudioOnly = metadata["proxy_type"] == "audio_only"
                val audioUrl = metadata["audio_url"]

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


