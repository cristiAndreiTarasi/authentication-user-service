package example.com.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import example.com.routes.dtos.SrsHookPayload
import example.com.schemas.StreamSchema
import example.com.schemas.UserSchema
import example.com.services.redis.RedisManager
import example.com.services.token.ITokenService
import example.com.services.token.TokenService
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.application
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.errors.IOException
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

suspend fun ApplicationCall.respondSrsBool(ok: Boolean) {
    val body = if (ok) "0" else "1"
    // set a proper Content-Length and avoid chunked transfer encoding
    respondBytes(
        body.toByteArray(Charsets.UTF_8),
        contentType = ContentType.Text.Plain,
        status = HttpStatusCode.OK
    )
}

// SRS http hooks
fun Route.srsHttpHookRoutes(
    userSchema: UserSchema,
    publishTokenService: ITokenService,
    streamSchema: StreamSchema,
    httpClient: HttpClient,
    redisManager: RedisManager,
    moderationPublishSecret: String
) {
    route("/api/v1/streams") {
        // single POST endpoint — origin will call this
        post {
            val payload = try { call.receive<SrsHookPayload>() } catch (e: Exception) {
                application.log.warn("srsHttpHook: malformed payload", e)
                call.respondSrsBool(false); return@post
            }

            val ok = try {
                processSrsHook(call, payload, publishTokenService, streamSchema, userSchema, httpClient, redisManager, moderationPublishSecret)
            } catch (e: Exception) {
                application.log.error("srsHttpHook: handler error", e)
                false
            }

            call.respondSrsBool(ok)
        }
    }
}

// central dispatcher (keeps handlers unchanged)
private suspend fun processSrsHook(
    call: ApplicationCall,
    payload: SrsHookPayload,
    publishTokenService: ITokenService,
    streamSchema: StreamSchema,
    userSchema: UserSchema,
    httpClient: HttpClient,
    redisManager: RedisManager,
    moderationPublishSecret: String
): Boolean {
    val action = SrsHookAction.fromActionName(payload.action)

    return when (action) {
        SrsHookAction.ON_PUBLISH -> handleOnPublish(call, payload, publishTokenService, streamSchema, userSchema, httpClient, redisManager, moderationPublishSecret)
        SrsHookAction.ON_UNPUBLISH -> handleOnUnpublish(call, payload, publishTokenService, streamSchema, userSchema)
        SrsHookAction.ON_PLAY -> handleOnPlay(call, payload)
        SrsHookAction.ON_STOP -> handleOnStop(call, payload)
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
@Serializable
data class ModAuthReq(val stream_id: String, val token: String, val client_ip: String? = null, val app: String = "live")
@Serializable
data class ModAuthResp(val allow: Boolean, val reason: String? = null)

suspend fun callModerationController(
    streamKey: String,
    token: String,
    clientIp: String?,
    app: String = "live",
    httpClient: HttpClient
): Boolean {
    val req = ModAuthReq(streamKey, token, clientIp, app)

    return try {
        val resp = httpClient.post("http://moderation-controller:8090/internal/authorize_publish") {
            contentType(ContentType.Application.Json)
            setBody(req)
            timeout { requestTimeoutMillis = 900 } // consider bumping this
        }

        val respText = resp.bodyAsText() // raw textual body
        println("moderation response raw: $respText") // or log via logger passed in

        if (resp.status == HttpStatusCode.OK) {
            // parse explicitly to ensure we see what allow value is
            val parsed = kotlinx.serialization.json.Json.decodeFromString(ModAuthResp.serializer(), respText)
            println("moderation parsed: $parsed")
            parsed.allow
        } else {
            println("moderation returned non-200: ${resp.status}")
            false
        }
    } catch (e: Exception) {
        println("moderation call failed: $e",)
        false
    }
}

private suspend fun handleOnPublish(
    call: ApplicationCall,
    payload: SrsHookPayload,
    publishTokenService: ITokenService,
    streamSchema: StreamSchema,
    userSchema: UserSchema,
    httpClient: HttpClient,
    redisManager: RedisManager,
    moderationPublishSecret: String
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

        /// Check if this is a moderation token
        val isModerationToken = isModerationToken(token)
        if (isModerationToken) {
            log.info("on_publish: accepting moderation stream for ${payload.stream}")

            // For moderation streams, verify it's a valid moderation request
            val isValidModeration = verifyModerationToken(token, payload.stream, moderationPublishSecret)
            if (!isValidModeration) {
                log.warn("on_publish: invalid moderation token for ${payload.stream}")
                return false
            }

            // Check if stream exists and is in blocked state using Redis
            val streamState = getStreamModerationState(payload.stream, redisManager)
            if (streamState != "VIDEO_BLOCKED") {
                log.warn("on_publish: moderation stream attempted for non-blocked stream ${payload.stream}. Current state: $streamState")
                return false
            }

            log.info("on_publish: accepted moderation stream for blocked stream ${payload.stream}")
            return true
        }

        val streamKeyFromToken = publishTokenService.getClaimFromToken(token, "streamKey")
        val userIdStr = publishTokenService.getClaimFromToken(token, "userId")
        val jti = publishTokenService.getClaimFromToken(token, "jti") ?: ""
        val expClaim = publishTokenService.getClaimFromToken(token, "exp")

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

        val modOk = try {
            callModerationController(
                streamKey = streamKeyFromToken,
                token = token,
                clientIp = call.request.origin.remoteHost,
                httpClient = httpClient
            )
        } catch (e: Exception) {
            log.error("on_publish: moderation call threw", e)
            false
        }

        if (!modOk) {
            log.warn("on_publish: moderation-controller denied or timed out -> deny to SRS")
            return false
        }

        // moderation allowed, now perform DB atomic transition to publishing
        val marked = try {
            streamSchema.markPublishingIfNotAlready(streamKeyFromToken)
        } catch(e:Exception) {
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

private suspend fun getStreamModerationState(streamId: String, redisManager: RedisManager): String? {
    return try {
        // Get the stream state directly from Redis
        // The moderation controller stores state in Redis at key "stream:${streamId}"
        val redisKey = "stream:$streamId"

        // Use your existing RedisManager to get the state
        val metadata = redisManager.getStreamMetadata(streamId)
        metadata["state"]
    } catch (e: Exception) {
        // Log the error but don't fail the entire request
        println("Error getting stream moderation state from Redis for $streamId: ${e.message}")
        null
    }
}

private fun isModerationToken(token: String): Boolean {
    // Better heuristic: check if token contains moderation-specific claims
    return try {
        // Try to decode without verification first to check structure
        val decoded = JWT.decode(token)
        val moderationClaim = decoded.getClaim("moderation")
        moderationClaim != null && moderationClaim.asBoolean() == true
    } catch (e: Exception) {
        // If we can't decode, use length-based heuristic as fallback
        token.length > 50
    }
}

private fun verifyModerationToken(token: String, streamId: String, moderationPublishSecret: String): Boolean {
    return try {
        println("Verifying moderation token for stream: $streamId")
        println("Token length: ${token.length}")
        println("Using secret: ${moderationPublishSecret.take(10)}...") // Log first 10 chars for debugging

        val claims = JWT.require(Algorithm.HMAC256(moderationPublishSecret))
            .build()
            .verify(token)

        val tokenStreamId = claims.getClaim("streamKey").asString()
        val isModeration = claims.getClaim("moderation").asBoolean()
        val publicKey = claims.getClaim("publicKey").asString()

        println("Token claims - streamKey: $tokenStreamId, moderation: $isModeration, publicKey: $publicKey")

        val isValid = tokenStreamId == streamId && isModeration == true

        if (!isValid) {
            println("Token validation failed: streamId mismatch or not moderation token")
            println("Expected streamId: $streamId, Got: $tokenStreamId")
            println("Is moderation: $isModeration")
        }

        isValid
    } catch (e: Exception) {
        println("Token verification failed: ${e.message}")
        e.printStackTrace()
        false
    }
}

private suspend fun handleOnUnpublish(
    call: ApplicationCall,
    payload: SrsHookPayload,
    publishTokenService: ITokenService,
    streamSchema: StreamSchema,
    userSchema: UserSchema
): Boolean {
    val log = call.application.log
    try {
        log.info("on_unpublish: payload=$payload")
        val token = extractToken(payload.param ?: "")
        val streamKey = token?.let { publishTokenService.getClaimFromToken(it, "streamKey") } ?: payload.stream

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

private suspend fun handleOnPlay(call: ApplicationCall, payload: SrsHookPayload): Boolean {
    call.application.log.info("on_play: allowing playback for stream=${payload.stream}, client=${payload.clientId}")
    return true // Always allow playback
}

private suspend fun handleOnStop(call: ApplicationCall, payload: SrsHookPayload): Boolean {
    call.application.log.info("on_stop: client stopped playback for stream=${payload.stream}, client=${payload.clientId}")
    return true // Always accept
}

enum class SrsHookAction(val actionName: String) {
    ON_PUBLISH("on_publish"),
    ON_UNPUBLISH("on_unpublish"),
    ON_PLAY("on_play"),
    ON_STOP("on_stop");

    companion object {
        fun fromActionName(name: String): SrsHookAction? =
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
