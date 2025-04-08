package example.com.plugins

import example.com.services.socket.VisitorsManager
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import java.time.Duration

fun Application.configureSockets() {
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(15)
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    routing {
        webSocket("/ws/stream/{streamId}") {
            val streamId = call.parameters["streamId"]

            if (streamId.isNullOrBlank()) {
                close(
                    CloseReason(
                        CloseReason.Codes.CANNOT_ACCEPT,
                        "Missing streamId parameter"
                    )
                )
                return@webSocket
            }

            // Register this session for the stream.
            VisitorsManager.addSession(streamId, this)

            try {
                // Optionally, send an initial state (for example, load the cached data from Redis)
                // val initialState = redisCache.get<VisitorUpdateDto>("stream:$streamId:visitors")
                // if (initialState != null) send(Frame.Text(Json.encodeToString(initialState)))

                // Keep the connection open.
                for (frame in incoming) {
                    // You could handle incoming messages here if needed.
                }
            } catch (e: Exception) {
                // Optionally log the exception.
            } finally {
                // Clean up when the session closes.
                VisitorsManager.removeSession(streamId, this)
            }
        }
    }
}

