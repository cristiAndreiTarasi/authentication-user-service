package example.com.plugins

import example.com.routes.dtos.ChatMessageIn
import example.com.routes.dtos.ChatMessageOut
import example.com.routes.dtos.LikeUpdate
import example.com.routes.dtos.PublisherDisconnected
import example.com.routes.dtos.PublisherInfo
import example.com.routes.dtos.UserJoined
import example.com.routes.dtos.VisitorCountUpdate
import example.com.schemas.ExposedUser
import example.com.schemas.UserSchema
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
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPubSub
import java.time.Duration
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

@OptIn(DelicateCoroutinesApi::class)
fun Application.configureSockets(
    userSchema: UserSchema,
    tokenService: TokenService,
    streamSessionsMap: ConcurrentHashMap<String, MutableList<DefaultWebSocketServerSession>>,
    streamSubscribersMap: ConcurrentHashMap<String, Boolean>
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

            // Track all sessions for this stream
            val sessions = streamSessionsMap.getOrPut(streamId) {
                Collections.synchronizedList(mutableListOf())
            }
            sessions.add(this)

            val commandJedis = jedisPool.resource

            // Connection logic
            if (isStreamer) {
                // New streamer: store their userId and reset counters
                commandJedis.hmset(pubKey, mapOf("userId" to userId))
                commandJedis.set(visCountKey, "0")
                commandJedis.set(lCountKey, "0")
            } else {
                // For viewers: increment visitor count.
                // ── A viewer just connected ──
                commandJedis.incr(visCountKey)
                val currentCount = commandJedis.get(visCountKey).toInt()
                val visitorEvent = VisitorCountUpdate(currentCount = currentCount)
                commandJedis.publish(visCh, Json.encodeToString(visitorEvent))

                // Publish “X joined the chat” as a system event
                val user     = userSchema.findById(userId.toInt())
                 val username = user?.username ?: "Unknown"
                val joined   = UserJoined(userId = userId, username = username)
                commandJedis.publish(chatCh, Json.encodeToString(joined))

                // Additionally, fetch and publish the current like count.
                val currentLikeCount = commandJedis.get(lCountKey).toInt()
                val likeUpdate = LikeUpdate(newCount = currentLikeCount)
                commandJedis.publish(likeCh, Json.encodeToString(likeUpdate))
            }

            // If I’m a viewer, immediately send publisher info
            if (!isStreamer) {
                val publisherInfo = commandJedis.hgetAll(pubKey)
                val publisherId = publisherInfo["userId"]
                if (publisherId != null) {
                    val publisherEvent = PublisherInfo(userId = publisherId)
                    outgoing.send(Frame.Text(Json.encodeToString(publisherEvent)))
                }
            }

            // Subscribe once per stream to fan out messages
            var pubSubJedis: Jedis? = null
            var pubSub: JedisPubSub? = null
            if (streamSubscribersMap.putIfAbsent(streamId, true) == null) {
                pubSub = object : JedisPubSub() {
                    override fun onMessage(channel: String, message: String) {
                        GlobalScope.launch {
                            val dead = mutableListOf<DefaultWebSocketServerSession>()
                            // Fan out to *all* sessions of this stream
                            streamSessionsMap[streamId]?.forEach { sess ->
                                try {
                                    sess.outgoing.send(Frame.Text(message))
                                } catch (e: Exception) {
                                    dead.add(sess)
                                }
                            }
                            streamSessionsMap[streamId]?.removeAll(dead)
                        }
                    }
                }
                pubSubJedis = jedisPool.resource
                launch(Dispatchers.IO) {
                    try {
                        pubSubJedis.subscribe(pubSub, chatCh, likeCh, visCh)
                    } catch (e: Exception) {
                        // Cleanup if subscribe fails
                        streamSubscribersMap.remove(streamId)
                        pubSubJedis.close()
                    }
                }
            }

            // Incoming‑frame loop
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
                                val incomingChat = Json.decodeFromString(
                                    ChatMessageIn.serializer(), text
                                )

                                val enriched = ChatMessageOut(
                                    userId   = incomingChat.userId,
                                    username = incomingChat.username,
                                    message  = incomingChat.message
                                )

                                commandJedis.publish(chatCh, Json.encodeToString(enriched))
                            }

                        }
                    }
                }
            } finally {
                // Clean up this session
                sessions.remove(this)
                if (sessions.isEmpty()) {
                    streamSessionsMap.remove(streamId)
                    streamSubscribersMap.remove(streamId)
                    pubSub?.unsubscribe()
                    pubSubJedis?.close()
                }

                // Viewer disconnect: decrement visitor count
                if (!isStreamer) {
                    val updated = commandJedis.decr(visCountKey).toInt()
                    commandJedis.publish(visCh, Json.encodeToString(
                        VisitorCountUpdate(currentCount = updated)
                    ))
                }

                // Streamer disconnect: reset everything + notify
                if (isStreamer) {
                    commandJedis.set(visCountKey, "0")
                    commandJedis.set(lCountKey, "0")
                    commandJedis.del(pubKey)

                    commandJedis.publish(visCh, Json.encodeToString(
                        VisitorCountUpdate(currentCount = 0)
                    ))
                    commandJedis.publish(likeCh, Json.encodeToString(
                        LikeUpdate(newCount = 0)
                    ))
                    commandJedis.publish(visCh, Json.encodeToString(
                        PublisherDisconnected()
                    ))
                }

                commandJedis.close()
            }
        }
    }
}