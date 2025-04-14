package example.com.plugins

import example.com.routes.dtos.BroadcastEvent
import example.com.services.role.RoleService
import example.com.services.token.TokenService
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPubSub
import java.time.Duration
import java.util.Collections

@OptIn(DelicateCoroutinesApi::class)
fun Application.configureSockets(
    tokenService: TokenService,
    roleService: RoleService
) {
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(15)
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    // Read Redis config.
    val redisHost = environment.config.propertyOrNull("db.redis.host")?.getString() ?: "localhost"
    val redisPort = environment.config.propertyOrNull("db.redis.port")?.getString()?.toIntOrNull() ?: 6379
    val jedisPool = JedisPool(redisHost, redisPort)

    // Keys and channels.
    val chatChannel = "broadcast:chat"
    val likeChannel = "broadcast:like"
    val visitorChannel = "broadcast:visitors"
    val visitorCountKey = "broadcast:visitors"

    // Initialize visitor counter.
    jedisPool.resource.use { jedis ->
        if (jedis.get(visitorCountKey) == null) {
            jedis.set(visitorCountKey, "0")
        }
    }

    val sessions = Collections.synchronizedList(mutableListOf<DefaultWebSocketServerSession>())

    // Pub/Sub connection.
    val pubSubJedis = jedisPool.resource
    val pubSub = object : JedisPubSub() {
        override fun onMessage(channel: String, message: String) {
            GlobalScope.launch {
                val deadSessions = mutableListOf<DefaultWebSocketServerSession>()
                sessions.forEach { session ->
                    try {
                        session.outgoing.send(Frame.Text(message))
                    } catch (e: Exception) {
                        deadSessions.add(session)
                    }
                }
                sessions.removeAll(deadSessions)
            }
        }
    }
    launch(Dispatchers.IO) {
        pubSubJedis.subscribe(pubSub, chatChannel, likeChannel, visitorChannel)
    }

    routing {
        webSocket("/ws") {
            // Retrieve query parameters.
            val streamId = call.request.queryParameters["streamId"]
            val isStreamerLocal = call.request.queryParameters["isStreamer"]?.toBoolean() ?: false

            // Grab a command connection.
            val commandJedis = jedisPool.resource
            sessions.add(this)

            if (!isStreamerLocal) {
                // For viewers, increment visitor count.
                commandJedis.incr(visitorCountKey)
                val currentCount = commandJedis.get(visitorCountKey).toInt()
                val visitorEvent = BroadcastEvent.VisitorCountUpdate(currentCount = currentCount)
                commandJedis.publish(visitorChannel, Json.encodeToString(visitorEvent))
            } else {
                println("Streamer connected for stream $streamId")
            }

            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val text = frame.readText()
                        if (text.contains("\"type\":\"like\"")) {
                            val newLikeCount = commandJedis.incr("broadcast:likes")
                            val likeUpdate = BroadcastEvent.LikeUpdate(newCount = newLikeCount.toInt())
                            commandJedis.publish(likeChannel, Json.encodeToString(likeUpdate))
                        } else {
                            val chatMessage = Json.decodeFromString(BroadcastEvent.ChatMessage.serializer(), text)
                            commandJedis.publish(chatChannel, Json.encodeToString(chatMessage))
                        }
                    }
                }
            } finally {
                sessions.remove(this)
                if (!isStreamerLocal) {
                    // For viewers, decrement visitor count.
                    commandJedis.decr(visitorCountKey)
                    val updatedCount = commandJedis.get(visitorCountKey).toInt()
                    val leaveEvent = BroadcastEvent.VisitorCountUpdate(currentCount = updatedCount)
                    commandJedis.publish(visitorChannel, Json.encodeToString(leaveEvent))
                } else {
                    // For streamer disconnect, reset counters completely.
                    commandJedis.set(visitorCountKey, "0")
                    commandJedis.del("broadcast:likes")
                    val resetVisitorEvent = BroadcastEvent.VisitorCountUpdate(currentCount = 0)
                    commandJedis.publish(visitorChannel, Json.encodeToString(resetVisitorEvent))
                    val likeResetEvent = BroadcastEvent.LikeUpdate(newCount = 0)
                    commandJedis.publish(likeChannel, Json.encodeToString(likeResetEvent))
                }
                commandJedis.close()
            }
        }
    }
}



