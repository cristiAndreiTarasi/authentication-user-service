package example.com.plugins

import example.com.WS_JSON
import example.com.routes.dtos.BroadcastEvent
import example.com.routes.dtos.ChatMessageIn
import example.com.routes.dtos.ChatMessageOut
import example.com.routes.dtos.GrantModerator
import example.com.routes.dtos.GrantModeratorIn
import example.com.routes.dtos.KickUserEvent
import example.com.routes.dtos.KickUserIn
import example.com.routes.dtos.LikeUpdate
import example.com.routes.dtos.MuteUserEvent
import example.com.routes.dtos.MuteUserIn
import example.com.routes.dtos.PublisherDisconnected
import example.com.routes.dtos.PublisherInfo
import example.com.routes.dtos.RevokeModerator
import example.com.routes.dtos.RevokeModeratorIn
import example.com.routes.dtos.UnmuteUserEvent
import example.com.routes.dtos.UnmuteUserIn
import example.com.routes.dtos.UserJoined
import example.com.routes.dtos.UserSession
import example.com.routes.dtos.VisitorCountUpdate
import example.com.schemas.UserSchema
import example.com.services.token.TokenService
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopPreparing
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
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands
import io.lettuce.core.pubsub.RedisPubSubListener
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

@OptIn(DelicateCoroutinesApi::class)
fun Application.configureSockets(
    userSchema: UserSchema,
    tokenService: TokenService,
    streamSessionsMap: ConcurrentHashMap<String, MutableList<UserSession>>,
    streamSubscribersMap: ConcurrentHashMap<String, Boolean>
) {
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(15)
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    // Lettuce Redis Setup ---------------------------------------------------
    val redisHost = environment.config.propertyOrNull("db.redis.host")?.getString() ?: "localhost"
    val redisPort = environment.config.propertyOrNull("db.redis.port")?.getString()?.toIntOrNull() ?: 6379

    val redisClient = RedisClient.create("redis://$redisHost:$redisPort")
    val redisConnection: StatefulRedisConnection<String, String> = redisClient.connect()
    val commands: RedisCommands<String, String> = redisConnection.sync()
    val pubSubConnection: StatefulRedisPubSubConnection<String, String> = redisClient.connectPubSub()

    val activeSubscriptions = ConcurrentHashMap<String, RedisPubSubListener<String, String>>()

    // Key/Channel functions unchanged...
    fun rolesKey(streamId: String) = "stream:$streamId:roles"
    fun publisherKey(streamId: String) = "stream:$streamId:publisher"
    fun visitorCountKey(streamId: String) = "stream:$streamId:visitors"
    fun likeCountKey(streamId: String) = "stream:$streamId:likes"
    fun chatChannel(streamId: String) = "stream:$streamId:chat"
    fun likeChannel(streamId: String) = "stream:$streamId:like"
    fun visitorChannel(streamId: String) = "stream:$streamId:visitors"

    fun rank(role: String) = when(role) {
        "publisher" -> 3
        "moderator" -> 2
        else -> 1
    }

    suspend fun DefaultWebSocketServerSession.sendSerialized(event: BroadcastEvent) {
        send(Frame.Text(WS_JSON.encodeToString(event)))
    }

    // Helper function for thread-safe session cleanup
    fun removeDeadSessions(sessions: MutableList<UserSession>, dead: List<UserSession>) {
        synchronized(sessions) {
            sessions.removeAll(dead.toSet())
        }
    }

    routing {
        webSocket("/ws") {
            // Parameter parsing unchanged...
            val streamId = call.request.queryParameters["streamId"] ?: run {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Missing streamId"))
                return@webSocket
            }
            val isStreamer = call.request.queryParameters["isStreamer"]?.toBoolean() ?: false
            val token = call.request.queryParameters["token"] ?: run {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Missing token"))
                return@webSocket
            }
            val userId = tokenService.getClaimFromToken(token, "userId") ?: run {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Invalid token"))
                return@webSocket
            }

            // Redis keys/channels
            val rolesRedisKey = rolesKey(streamId)
            val pubKey = publisherKey(streamId)
            val visCountKey = visitorCountKey(streamId)
            val lCountKey = likeCountKey(streamId)
            val chatCh = chatChannel(streamId)
            val likeCh = likeChannel(streamId)
            val visCh = visitorChannel(streamId)

            // Session tracking
            val sessions = streamSessionsMap.getOrPut(streamId) {
                Collections.synchronizedList(mutableListOf<UserSession>())
            }
            val currentUserSession = UserSession(this, userId)
            sessions.add(currentUserSession)

            // Connection Logic ----------------------------------------------
            if (isStreamer) {
                commands.hset(pubKey, "userId", userId)
                commands.expire(rolesRedisKey, 3600)
                commands.hset(rolesRedisKey, userId, "publisher")
                commands.set(visCountKey, "0")
                commands.set(lCountKey, "0")
            } else {
                commands.incr(visCountKey)
                val currentCount = commands.get(visCountKey).toInt()
                commands.publish(visCh, WS_JSON.encodeToString(VisitorCountUpdate(currentCount = currentCount)))

                val user = userSchema.findById(userId.toInt())
                val username = user?.username ?: "Unknown"
                commands.publish(chatCh, WS_JSON.encodeToString(UserJoined(userId = userId, username = username)))

                val currentLikeCount = commands.get(lCountKey).toInt()
                commands.publish(likeCh, WS_JSON.encodeToString(LikeUpdate(newCount = currentLikeCount)))
            }

            // Publisher info for viewers
            if (!isStreamer) {
                val publisherId = commands.hget(pubKey, "userId")
                publisherId?.let {
                    send(Frame.Text(WS_JSON.encodeToString(PublisherInfo(userId = it))))
                }
            }

            // PubSub Setup --------------------------------------------------
            if (streamSubscribersMap.putIfAbsent(streamId, true) == null) {
                val listener = object : RedisPubSubListener<String, String> {
                    override fun message(channel: String, message: String) {
                        launch {
                            val dead = mutableListOf<UserSession>() // Correct type
                            sessions.forEach { userSession ->
                                try {
                                    userSession.wsSession.outgoing.send(Frame.Text(message))
                                } catch (e: Exception) {
                                    dead.add(userSession)
                                }
                            }

                            removeDeadSessions(sessions, dead)

                            if (dead.isNotEmpty()) {
                                println("Cleaned up ${dead.size} dead sessions for stream $streamId")
                            }
                        }
                    }

                    override fun message(pattern: String, channel: String, message: String) {}
                    override fun subscribed(channel: String, count: Long) {}
                    override fun psubscribed(pattern: String, count: Long) {}
                    override fun unsubscribed(channel: String, count: Long) {}
                    override fun punsubscribed(pattern: String, count: Long) {}
                }

                pubSubConnection.addListener(listener)
                pubSubConnection.sync().subscribe(chatCh, likeCh, visCh)
                activeSubscriptions[streamId] = listener
            }

            // Message Processing Loop ---------------------------------------
            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue

                    val text = frame.readText()
                    val json = WS_JSON.parseToJsonElement(text).jsonObject

                    when(json["type"]?.jsonPrimitive?.content) {
                        "chat_message" -> {
                            val msg = WS_JSON.decodeFromString<ChatMessageIn>(text)
                            commands.publish(chatCh, WS_JSON.encodeToString(
                                ChatMessageOut(userId = msg.userId, username = msg.username, message = msg.message)
                            ))
                        }

                        "like" -> {
                            val newCount = commands.incr(lCountKey).toInt()
                            commands.publish(likeCh, WS_JSON.encodeToString(
                                LikeUpdate(newCount = newCount)
                            ))
                        }

                        "grant_moderator" -> {
                            val cmd = WS_JSON.decodeFromString<GrantModeratorIn>(text)
                            val actorRole = commands.hget(rolesRedisKey, userId) ?: "viewer"
                            val targetRole = commands.hget(rolesRedisKey, cmd.targetUserId) ?: "viewer"

                            if (rank(actorRole) > rank(targetRole)) {
                                commands.hset(rolesRedisKey, cmd.targetUserId, "moderator")
                                streamSessionsMap[streamId]?.find { it.userId == cmd.targetUserId }?.wsSession?.let { session ->
                                    session.send(Frame.Text(WS_JSON.encodeToString(
                                        GrantModerator(targetUserId = cmd.targetUserId)
                                    )))
                                }

                                commands.publish(chatCh, WS_JSON.encodeToString(
                                    ChatMessageOut(
                                        userId = "system",
                                        username = "",
                                        message = "${cmd.targetUserId} was granted moderator privileges",
                                        type = "system"
                                    )
                                ))
                            }
                        }

                        "kick_user" -> {
                            val cmd = WS_JSON.decodeFromString<KickUserIn>(text)
                            val actorRole = commands.hget(rolesRedisKey, userId) ?: "viewer"
                            val targetRole = commands.hget(rolesRedisKey, cmd.targetUserId) ?: "viewer"

                            if (rank(actorRole) > rank(targetRole)) {
                                // 3. Broadcast system message
                                commands.publish(chatCh, WS_JSON.encodeToString(
                                    ChatMessageOut(
                                        userId = "system",
                                        username = "",
                                        message = "Kicked by moderator",
                                        type = "system"
                                    )
                                ))
                                // Find target session safely
                                streamSessionsMap[streamId]?.find { it.userId == cmd.targetUserId }?.let { userSession ->
                                    launch {
                                        // 1. Notify target user
                                        userSession.wsSession.send(Frame.Text(WS_JSON.encodeToString(
                                            KickUserEvent(
                                                targetUserId = cmd.targetUserId,
                                                reason = "Kicked by moderator"
                                            )
                                        )))

                                        // 2. Close connection
                                        userSession.wsSession.close(
                                            CloseReason(CloseReason.Codes.NORMAL, "Kicked by moderator")
                                        )
                                    }
                                }
                            }
                        }

                        "mute_user" -> {
                            val cmd = WS_JSON.decodeFromString<MuteUserIn>(text)
                            val actorRole = commands.hget(rolesRedisKey, userId) ?: "viewer"
                            val targetRole = commands.hget(rolesRedisKey, cmd.targetUserId) ?: "viewer"

                            if (rank(actorRole) > rank(targetRole)) {
                                // 1. Set mute in Redis (15 second expiry)
                                commands.setex(
                                    "muted:${cmd.targetUserId}",
                                    cmd.durationMs / 1000,
                                    "1"
                                )

                                // 2. Notify target user
                                streamSessionsMap[streamId]?.find { it.userId == cmd.targetUserId }?.wsSession?.let { session ->
                                    session.send(Frame.Text(WS_JSON.encodeToString(
                                        MuteUserEvent(
                                            targetUserId = cmd.targetUserId,
                                            durationMs = cmd.durationMs
                                        )
                                    )))
                                }

                                // 3. Optional: Broadcast mute notification
                                commands.publish(chatCh, WS_JSON.encodeToString(
                                    ChatMessageOut(
                                        userId = "system",
                                        username = "",
                                        message = "User ${cmd.targetUserId} was muted for ${cmd.durationMs/1000}s",
                                        type = "system"
                                    )
                                ))
                            }
                        }

                        "unmute_user" -> {
                            val cmd = WS_JSON.decodeFromString<UnmuteUserIn>(text)
                            val actorRole = commands.hget(rolesRedisKey, userId) ?: "viewer"
                            val targetRole = commands.hget(rolesRedisKey, cmd.targetUserId) ?: "viewer"

                            if (rank(actorRole) > rank(targetRole)) {
                                // 1. Remove mute in Redis
                                commands.del("muted:${cmd.targetUserId}")

                                // 2. Notify target user
                                streamSessionsMap[streamId]?.find { it.userId == cmd.targetUserId }
                                    ?.wsSession?.send(Frame.Text(WS_JSON.encodeToString(
                                        UnmuteUserEvent(targetUserId = cmd.targetUserId)
                                    )
                                ))
                            }
                        }

                        "revoke_moderator" -> {
                            val cmd = WS_JSON.decodeFromString<RevokeModeratorIn>(text)
                            val actorRole = commands.hget(rolesRedisKey, userId) ?: "viewer"
                            val targetRole = commands.hget(rolesRedisKey, cmd.targetUserId) ?: "viewer"

                            if (rank(actorRole) > rank(targetRole)) {
                                // 1. Remove moderator role
                                commands.hdel(rolesRedisKey, cmd.targetUserId)

                                // 2. Notify target user
                                streamSessionsMap[streamId]?.find { it.userId == cmd.targetUserId }?.wsSession?.let { session ->
                                    session.send(Frame.Text(WS_JSON.encodeToString(
                                        RevokeModerator(targetUserId = cmd.targetUserId)
                                    )))
                                }

                                commands.publish(chatCh, WS_JSON.encodeToString(
                                    ChatMessageOut(
                                        userId = "system",
                                        username = "",
                                        message = "${cmd.targetUserId} had moderator privileges revoked",
                                        type = "system"
                                    )
                                ))
                            }
                        }
                    }
                }
            } finally {
                // Cleanup
                sessions.remove(currentUserSession)

                if (sessions.isEmpty()) {
                    activeSubscriptions[streamId]?.let {
                        pubSubConnection.removeListener(it)
                        pubSubConnection.sync().unsubscribe(chatCh, likeCh, visCh)
                    }
                    streamSessionsMap.remove(streamId)
                    streamSubscribersMap.remove(streamId)
                }

                if (!isStreamer) {
                    val updated = commands.decr(visCountKey).toInt()
                    commands.publish(visCh, WS_JSON.encodeToString(
                        VisitorCountUpdate(currentCount = updated)
                    ))
                } else {
                    // Streamer cleanup
                    commands.set(visCountKey, "0")
                    commands.set(lCountKey, "0")
                    commands.del(pubKey)
                    commands.publish(visCh, WS_JSON.encodeToString(VisitorCountUpdate(currentCount = 0)))
                    commands.publish(likeCh, WS_JSON.encodeToString(LikeUpdate(newCount =0)))
                    commands.publish(visCh, WS_JSON.encodeToString(PublisherDisconnected()))
                }
            }
        }
    }

    // Shutdown Hook
    environment.monitor.subscribe(ApplicationStopPreparing) {
        pubSubConnection.close()
        redisConnection.close()
        redisClient.shutdown()
    }
}