package example.com.routes

import com.auth0.jwt.JWT
import com.sun.tools.javac.util.Log
import example.com.routes.dtos.SrsHookPayload
import example.com.schemas.UserSchema
import example.com.services.redis.VisitorCache
import example.com.services.socket.VisitorDto
import example.com.services.socket.VisitorsManager
import example.com.services.token.TokenService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.errors.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

fun Route.srsHttpHookRoutes(
    userSchema: UserSchema,
    tokenService: TokenService,
) {
    route("/api/v1") {
        post("/streams") {
            val payload = call.receive<SrsHookPayload>()
            val action = SrsHookStreams.fromActionName(payload.action)
            val token = extractToken(payload.param)

            if (token == null) {
                call.respondText("Missing token", status = HttpStatusCode.BadRequest)
                return@post
            }

            val userId = tokenService.getClaimFromToken(token, "userId")

            if (userId == null) {
                call.respondText("Invalid token", status = HttpStatusCode.BadRequest)
                return@post
            }

            val user = userSchema.findById(userId.toInt())

            if (user == null) {
                call.respond(HttpStatusCode.BadRequest, "User not found.")
                return@post
            }

            when (action) {
                SrsHookStreams.ON_PUBLISH -> {
                    if (handleOnPublish(payload)) {
                        userSchema.updateIsStreaming(userId.toInt(), !user.isLive)
                        call.respondText("0", status = HttpStatusCode.OK)
                    } else {
                        call.respondText("1", status = HttpStatusCode.BadRequest)
                    }
                }

                SrsHookStreams.ON_UNPUBLISH -> {
                    if (handleOnUnpublish(payload)) {
                        userSchema.updateIsStreaming(userId.toInt(), !user.isLive)
                        call.respondText("0", status = HttpStatusCode.OK)
                    } else {
                        call.respondText("1", status = HttpStatusCode.BadRequest)
                    }
                }

                null -> call.respondText("Invalid action", status = HttpStatusCode.BadRequest)
            }
        }

        post("/sessions") {
            val payload = call.receive<SrsHookPayload>()
            val action = SrsHookSessions.fromActionName(payload.action)
            val token = extractToken(payload.param)

            if (token == null) {
                call.respondText("Missing token", status = HttpStatusCode.BadRequest)
                return@post
            }

            val userId = tokenService.getClaimFromToken(token, "userId")

            if (userId == null) {
                call.respondText("Invalid token", status = HttpStatusCode.BadRequest)
                return@post
            }

            val visitor = VisitorDto(
                userId = userId,
//                name = payload.name,
//                avatarUrl = payload.avatarUrl
            )

            val visitorCache = VisitorCache()

            when (action) {
                SrsHookSessions.ON_PLAY -> {
                    if (handleOnPlay(payload)) {
                        val updatedVisitorData = visitorCache.incrementVisitorCount(streamId, visitor)
                        VisitorsManager.broadcastVisitorUpdate(streamId, updatedVisitorData)

                        call.respondText("0", status = HttpStatusCode.OK)
                    } else {
                        call.respondText("1", status = HttpStatusCode.BadRequest)
                    }
                }

                SrsHookSessions.ON_STOP -> {
                    if (handleOnStop(payload)) {
                        val updatedVisitorData = visitorCache.decrementVisitorCount(streamId, visitor)
                        VisitorsManager.broadcastVisitorUpdate(streamId, updatedVisitorData)

                        call.respondText("0", status = HttpStatusCode.OK)
                    } else {
                        call.respondText("1", status = HttpStatusCode.BadRequest)
                    }
                }

                null -> call.respondText("Invalid action", status = HttpStatusCode.BadRequest)
            }
        }
    }
}

/**
 * Extracts the token from a URL parameter string.
 * Handles both RTMP (`?token=...`) and SRT (`secret=...`) formats.
 *
 * @param param the parameter string from the URL
 * @return the extracted token or null if not found
 */
fun extractToken(param: String): String? {
    // Look for ?token= for RTMP
    val tokenFromRtmp = param.substringAfter("?token=", "").substringBefore("&")
    if (tokenFromRtmp.isNotEmpty()) return tokenFromRtmp

    // Look for secret= for SRT
    val tokenFromSrt = param.substringAfter("secret=", "").substringBefore("&")
    if (tokenFromSrt.isNotEmpty()) return tokenFromSrt

    // Token not found in either format
    return null
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

fun handleOnPublish(payload: SrsHookPayload): Boolean {
    // Handle stream publishing logic, e.g., validate token, log activity
    println("on_publish: ${payload}")
    return true
}

fun handleOnUnpublish(payload: SrsHookPayload): Boolean {
    // Handle stream unpublishing logic
    println("on_unpublish: ${payload}")
    return true
}

fun handleOnPlay(
    payload: SrsHookPayload,
): Boolean {
    // Handle stream play logic
    println("on_play: ${payload.stream}")
    return true
}

fun handleOnStop(
    payload: SrsHookPayload
): Boolean {
    println("on_stop: ${payload.stream}")
    return true
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


