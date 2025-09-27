package example.com.routes

import example.com.routes.dtos.SrsHookPayload
import example.com.schemas.StreamSchema
import example.com.schemas.UserSchema
import example.com.services.token.TokenService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.application
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.errors.IOException
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

// SRS http hooks
fun Route.srsHttpHookRoutes(
    userSchema: UserSchema,
    tokenService: TokenService,
    streamSchema: StreamSchema
) {
    route("/api/v1/streams") {
        // single POST endpoint — origin will call this
        post {
            val call = this.call
            val log = call.application.log

            val payload = try {
                call.receive<SrsHookPayload>()
            } catch (e: Exception) {
                log.warn("srsHttpHook: malformed payload", e)
                // Respond "1" (deny) but with 200 to avoid SRS treating this as hook failure
                call.respondText("1", status = HttpStatusCode.OK)
                return@post
            }

            log.debug("srsHttpHook: received payload=$payload")

            val ok: Boolean = try {
                processSrsHook(call, payload, tokenService, streamSchema, userSchema)
            } catch (e: Exception) {
                log.error("srsHttpHook: handler threw", e)
                false
            }

            // Always 200; body "0" = OK, "1" = deny.
            if (ok) call.respondText("0", status = HttpStatusCode.OK)
            else call.respondText("1", status = HttpStatusCode.OK)
        }
    }
}

// central dispatcher (keeps handlers unchanged)
private suspend fun processSrsHook(
    call: ApplicationCall,
    payload: SrsHookPayload,
    tokenService: TokenService,
    streamSchema: StreamSchema,
    userSchema: UserSchema
): Boolean {
    val action = SrsHookStreams.fromActionName(payload.action)
    return when (action) {
        SrsHookStreams.ON_PUBLISH -> handleOnPublish(call, payload, tokenService, streamSchema, userSchema)
        SrsHookStreams.ON_UNPUBLISH -> handleOnUnpublish(call, payload, tokenService, streamSchema, userSchema)
        else -> {
            call.application.log.warn("srsHttpHook: unknown action=${payload.action}")
            false
        }
    }
}

fun extractToken(param: String): String? {
    if (param.isBlank()) return null

    // decode in case SRS forwarded an encoded param
    val decoded = try {
        URLDecoder.decode(param, StandardCharsets.UTF_8.name())
    } catch (_: Exception) {
        param
    }

    // Look for ?token= for RTMP, or token= in general
    val queryToken = decoded.substringAfter("?token=", "").substringBefore("&")
        .takeIf { it.isNotBlank() } ?: decoded.substringAfter("token=", "").substringBefore("&").takeIf { it.isNotBlank() }
    if (!queryToken.isNullOrBlank()) return queryToken

    // Look for secret= for SRT-like params (and more complex forms)
    val secretToken = decoded.substringAfter("secret=", "").substringBefore("&").substringBefore(",")
    if (!secretToken.isNullOrBlank()) return secretToken

    // try regex for secret in complex param strings (e.g. #!::r=...,secret=...,m=publish)
    Regex("secret=([^,&\\s]+)").find(decoded)?.groupValues?.getOrNull(1)?.let {
        if (it.isNotBlank()) return it
    }

    return null
}

// --- Handlers ---------------------------------------------------------------

suspend fun handleOnPublish(
    call: ApplicationCall,
    payload: SrsHookPayload,
    tokenService: TokenService,
    streamSchema: StreamSchema,
    userSchema: UserSchema // kept for parity/logging if needed
): Boolean {
    val log = call.application.log
    try {
        log.info("on_publish: payload=$payload")

        val rawParam = payload.param ?: ""
        val token = extractToken(rawParam)
        if (token.isNullOrBlank()) {
            log.warn("on_publish: no token in param='$rawParam', denying.")
            return false
        }

        val streamKeyFromToken = tokenService.getClaimFromToken(token, "streamKey")
        val userIdStr = tokenService.getClaimFromToken(token, "userId")
        val jti = tokenService.getClaimFromToken(token, "jti") ?: ""
        val expClaim = tokenService.getClaimFromToken(token, "exp")

        log.debug("on_publish: token claims streamKey=$streamKeyFromToken userId=$userIdStr jti=$jti exp=$expClaim")

        if (streamKeyFromToken.isNullOrBlank() || userIdStr.isNullOrBlank()) {
            log.warn("on_publish: token missing required claims -> deny")
            return false
        }

        // token expiry check (NumericDate seconds)
        val now = Clock.System.now()
        val tokenExpInstant: Instant? = try { expClaim?.toLongOrNull()?.let { Instant.fromEpochSeconds(it) } } catch (_: Exception) { null }
        if (tokenExpInstant != null && now > tokenExpInstant) {
            log.warn("on_publish: token expired -> deny")
            return false
        }

        // ensure SRS stream matches token
        if (streamKeyFromToken != payload.stream) {
            log.warn("on_publish: token.streamKey != srs.stream -> deny")
            return false
        }

        // load record
        val record = try { streamSchema.findByStreamKey(streamKeyFromToken) } catch (e: Exception) {
            log.error("on_publish: DB lookup failed for $streamKeyFromToken", e)
            return false
        }

        if (record == null) {
            log.warn("on_publish: no DB record for streamKey=$streamKeyFromToken -> deny")
            return false
        }

        if (record.userId.toString() != userIdStr) {
            log.warn("on_publish: owner mismatch token.userId=$userIdStr != db.userId=${record.userId} -> deny")
            return false
        }

        if (!record.publishTokenJti.isNullOrBlank() && record.publishTokenJti != jti) {
            log.warn("on_publish: stored jti='${record.publishTokenJti}' != token jti='$jti' -> deny")
            return false
        }

        // If DB already publishing, accept (idempotent)
        if (record.status.equals("publishing")) {
            log.info("on_publish: already publishing -> accept")
            return true
        }

        // If ended, only allow republish when jti matches and token valid
        if (record.status.equals("ended")) {
            if (record.publishTokenJti != jti) {
                log.warn("on_publish: record ended and jti mismatch -> deny")
                return false
            }
            if (tokenExpInstant != null && now > tokenExpInstant) {
                log.warn("on_publish: record ended but token expired -> deny")
                return false
            }
            // otherwise allow attempt to re-publish
        }

        // Attempt atomic transition to publishing; this function sets users.is_live true in same tx
        val marked = try {
            streamSchema.markPublishingIfNotAlready(streamKeyFromToken)
        } catch (e: Exception) {
            log.error("on_publish: failed to mark publishing for $streamKeyFromToken", e)
            return false
        }

        if (marked) {
            log.info("on_publish: marked publishing for $streamKeyFromToken -> accept")
            return true
        }

        // Race-case: re-read and accept if publishing
        val refreshed = try { streamSchema.findByStreamKey(streamKeyFromToken) } catch (e: Exception) {
            log.error("on_publish: re-read failed", e)
            return false
        }
        val nowPublishing = refreshed?.status?.equals("publishing")
        log.info("on_publish: race re-check -> status=${refreshed?.status}, accept=$nowPublishing")
        return nowPublishing!!
    } catch (e: Exception) {
        call.application.log.error("on_publish: unexpected error", e)
        // Deny purposefully on unexpected error
        return false
    }
}

suspend fun handleOnUnpublish(
    call: ApplicationCall,
    payload: SrsHookPayload,
    tokenService: TokenService,
    streamSchema: StreamSchema,
    userSchema: UserSchema
): Boolean {
    val log = call.application.log
    try {
        log.info("on_unpublish: payload=$payload")
        val token = extractToken(payload.param ?: "")
        val streamKey = token?.let { tokenService.getClaimFromToken(it, "streamKey") } ?: payload.stream

        if (streamKey.isNullOrBlank()) {
            log.warn("on_unpublish: no streamKey resolved; accept to avoid SRS retries.")
            return true
        }

        try {
            // markEndedIfNotAlready will update users.is_live atomically if it updates anything
            val marked = streamSchema.markEndedIfNotAlready(streamKey)
            if (!marked) {
                // Could be: stream already ended, wasn't publishing, or row was deleted earlier.
                // If the stream was deleted earlier, ensure delete path already reconciled user's is_live.
                log.info("on_unpublish: stream $streamKey was not publishing or already ended (marked=$marked)")
            } else {
                log.info("on_unpublish: stream $streamKey marked ended")
            }
        } catch (e: Exception) {
            log.error("on_unpublish: markEnded failed for $streamKey", e)
            // Accept to avoid SRS retry storms; we logged the error
            return true
        }

        return true
    } catch (e: Exception) {
        call.application.log.error("on_unpublish: unexpected error", e)
        // Accept to avoid SRS retry storms
        return true
    }
}

enum class SrsHookStreams(val actionName: String) {
    ON_PUBLISH("on_publish"),
    ON_UNPUBLISH("on_unpublish");

    companion object {
        fun fromActionName(name: String): SrsHookStreams? =
            entries.find { it.actionName == name }
    }
}

enum class SrsHookSessions(val actionName: String) {
    ON_PLAY("on_play"),
    ON_STOP("on_stop");

    companion object {
        fun fromActionName(name: String): SrsHookSessions? =
            entries.find { it.actionName == name }
    }
}

val httpClient = OkHttpClient()

fun purgeNginxCache(payload: SrsHookPayload): Boolean {
    println("Purging nginx cache for stream: ${payload.stream}")
    val basePath = "live/hls/${payload.stream}"

    // Define all purge targets.
    val purgeUrls = listOf(
        "http://nginx-hls/purge/$basePath/master.m3u8"
    ) + (0..4).flatMap { bitrate ->
        listOf(
            "http://nginx-hls/purge/$basePath/$bitrate/playlist.m3u8",
            "http://nginx-hls/purge/$basePath/$bitrate/segment_*.ts"
        )
    }

    var allPurgesSuccessful = true

    for (purgeUrl in purgeUrls) {
        val request = Request.Builder()
            .url(purgeUrl)
            .get()
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    println("Cache purged successfully for: $purgeUrl")
                } else {
                    println("Failed to purge cache. Status: ${response.code}")
                    allPurgesSuccessful = false
                }
            }
        } catch (e: IOException) {
            println("Error purging cache: ${e.message}")
            allPurgesSuccessful = false
        }
    }

    return allPurgesSuccessful
}

fun cleanupOriginHls(payload: SrsHookPayload): Boolean {
    println("Cleaning up origin HLS for stream: ${payload.stream}")
    // Assuming the origin HLS folder is on a shared volume.
    val streamDir = "/usr/local/srs/objs/nginx/html/hls/live/${payload.stream}"
    val markerFile = File("$streamDir/.stopped")

    try {
        if (!markerFile.exists()) {
            // Create the marker file
            if (File(streamDir).exists()) {
                markerFile.createNewFile()
                println("Marker file created for stream ${payload.stream}")
            } else {
                println("Stream directory $streamDir does not exist.")
                return false
            }
        } else {
            // Update its timestamp
            markerFile.setLastModified(System.currentTimeMillis())
            println("Marker file timestamp updated for stream ${payload.stream}")
        }
    } catch (e: Exception) {
        println("Failed to create/update marker file: ${e.message}")
        return false
    }

    return true
}


//UPDATE users u SET is_live = false WHERE u.is_live = true AND NOT EXISTS (SELECT 1 FROM streams s WHERE s.user_id = u.id AND s.status = 'publishing');