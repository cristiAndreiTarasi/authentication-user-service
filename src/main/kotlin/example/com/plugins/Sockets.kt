package example.com.plugins

import example.com.routes.dtos.BroadcastEvent
import example.com.routes.dtos.ChatMessage
import example.com.routes.dtos.LikeUpdate
import example.com.routes.dtos.PublisherDisconnected
import example.com.routes.dtos.PublisherInfo
import example.com.routes.dtos.VisitorCountUpdate
import example.com.services.token.TokenService
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPubSub
import java.time.Duration
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

@OptIn(DelicateCoroutinesApi::class)
fun Application.configureSockets(
    tokenService: TokenService,
    streamSessionsMap: ConcurrentHashMap<String, MutableList<DefaultWebSocketServerSession>>
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

    // Keys and channels (scoped per stream).
    // These keys will be built using the stream ID.
    fun publisherKey(streamId: String) = "stream:$streamId:publisher"
    fun visitorCountKey(streamId: String) = "stream:$streamId:visitors"
    fun likeCountKey(streamId: String) = "stream:$streamId:likes"

    fun chatChannel(streamId: String) = "stream:$streamId:chat"
    fun likeChannel(streamId: String) = "stream:$streamId:like"
    fun visitorChannel(streamId: String) = "stream:$streamId:visitors"

    routing {
        webSocket("/ws") {
            // Retrieve query parameters.
            val streamId = call.request.queryParameters["streamId"]
            val isStreamer = call.request.queryParameters["isStreamer"]?.toBoolean() ?: false
            val token = call.request.queryParameters["token"]
            val userId = token?.let { tokenService.getClaimFromToken(it, "userId") }

            if (streamId == null || token == null || userId == null) {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Missing required parameters"))
                return@webSocket
            }

            val pubKey = publisherKey(streamId)
            val visCountKey = visitorCountKey(streamId)
            val lCountKey = likeCountKey(streamId)

            val chatCh = chatChannel(streamId)
            val likeCh = likeChannel(streamId)
            val visCh = visitorChannel(streamId)

            // Get the shared sessions list for this stream.
            // If not exists, create a new thread-safe list.
            val sessions = streamSessionsMap.getOrPut(streamId) {
                Collections.synchronizedList(mutableListOf())
            }
            sessions.add(this)

            val commandJedis = jedisPool.resource

            // Store publisher info
            if (isStreamer) {
                // For publisher: store publisher info and initialize counters.
                commandJedis.hmset(pubKey, mapOf("userId" to userId))
                println("Stored publisher info for stream $streamId: userId=$userId")
                commandJedis.set(visCountKey, "0")
                commandJedis.set(lCountKey, "0")
            } else {
                // For viewers: increment visitor count.
                commandJedis.incr(visCountKey)
                val currentCount = commandJedis.get(visCountKey).toInt()
                val visitorEvent = VisitorCountUpdate(currentCount = currentCount)
                commandJedis.publish(visCh, Json.encodeToString(visitorEvent))

                // Additionally, fetch and publish the current like count.
                val currentLikeCount = commandJedis.get(lCountKey).toInt()
                val likeUpdate = LikeUpdate(newCount = currentLikeCount)
                commandJedis.publish(likeCh, Json.encodeToString(likeUpdate))
            }

            if (!isStreamer) {
                // For visitors: read publisher info from Redis and send to this client.
                val publisherInfo = commandJedis.hgetAll(pubKey)
                val publisherId = publisherInfo["userId"]
                if (publisherId != null) {
                    val publisherEvent = PublisherInfo(userId = publisherId)
                    outgoing.send(Frame.Text(Json.encodeToString(publisherEvent)))
                }
            }

            // Set up a per-stream Pub/Sub using the global sessions list.
            val pubSub = object : JedisPubSub() {
                override fun onMessage(channel: String, message: String) {
                    GlobalScope.launch {
                        // Send the message to every session in the shared list.
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

            // Launch a subscriber for this stream's channels.
            val pubSubJedis = jedisPool.resource
            launch(Dispatchers.IO) {
                pubSubJedis.subscribe(pubSub, chatCh, likeCh, visCh)
            }

            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val text = frame.readText()
                        when {
                            text.contains("\"type\":\"like\"") -> {
                                val newCount = commandJedis.incr(lCountKey)
                                val likeUpdate = LikeUpdate(newCount = newCount.toInt())
                                commandJedis.publish(likeCh, Json.encodeToString(likeUpdate))
                            }
                            else -> {
                                val chatMessage = Json.decodeFromString(ChatMessage.serializer(), text)
                                commandJedis.publish(chatCh, Json.encodeToString(chatMessage))
                            }
                        }
                    }
                }
            } finally {
                sessions.remove(this)
                // If the shared sessions list is empty for this stream, remove it.
                if (sessions.isEmpty()) {
                    streamSessionsMap.remove(streamId)
                }
                if (isStreamer) {
                    // When the publisher disconnects: reset counters and remove publisher info.
                    commandJedis.set(visCountKey, "0")
                    commandJedis.set(lCountKey, "0")
                    commandJedis.del(pubKey)
                    val resetVisitorEvent = VisitorCountUpdate(currentCount = 0)
                    commandJedis.publish(visCh, Json.encodeToString(resetVisitorEvent))
                    val likeResetEvent = LikeUpdate(newCount = 0)
                    commandJedis.publish(likeCh, Json.encodeToString(likeResetEvent))
                } else {
                    // For a viewer disconnect: decrement the visitor counter.
                    commandJedis.decr(visCountKey)
                    val updatedCount = commandJedis.get(visCountKey).toInt()
                    val leaveEvent = VisitorCountUpdate(currentCount = updatedCount)
                    commandJedis.publish(visCh, Json.encodeToString(leaveEvent))

                    // Publish special event indicating publisher disconnected
                    val publisherDisconnectEvent = PublisherDisconnected()
                    commandJedis.publish(visCh, Json.encodeToString(publisherDisconnectEvent))
                }
                pubSub.unsubscribe()
                commandJedis.close()
                pubSubJedis.close()
            }
        }
    }
}



