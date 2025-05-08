package example.com.plugins

import example.com.WS_JSON
import example.com.routes.dtos.ChatMessageCommand
import example.com.routes.dtos.ChatMessageEvent
import example.com.routes.dtos.GrantModeratorCommand
import example.com.routes.dtos.KickUserCommand
import example.com.routes.dtos.LikeUpdateEvent
import example.com.routes.dtos.ModeratorActionSuccessEvent
import example.com.routes.dtos.ModeratorGrantedEvent
import example.com.routes.dtos.ModeratorRevokedEvent
import example.com.routes.dtos.MuteUserCommand
import example.com.routes.dtos.PublisherDisconnectedEvent
import example.com.routes.dtos.PublisherInfoEvent
import example.com.routes.dtos.RevokeModeratorCommand
import example.com.routes.dtos.UnmuteUserCommand
import example.com.routes.dtos.UserJoinedEvent
import example.com.routes.dtos.UserKickedEvent
import example.com.routes.dtos.UserMutedEvent
import example.com.routes.dtos.UserSession
import example.com.routes.dtos.UserUnmutedEvent
import example.com.routes.dtos.VisitorCountEvent
import example.com.routes.dtos.WsEvent
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
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

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

    suspend fun DefaultWebSocketServerSession.sendSerialized(event: WsEvent) {
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
                commands.publish(visCh, WS_JSON.encodeToString(VisitorCountEvent(currentCount = currentCount)))

                val user = userSchema.findById(userId.toInt())
                val username = user?.username ?: "Unknown"
                commands.publish(chatCh, WS_JSON.encodeToString(UserJoinedEvent(userId = userId, username = username)))

                val currentLikeCount = commands.get(lCountKey).toInt()
                commands.publish(likeCh, WS_JSON.encodeToString(LikeUpdateEvent(newCount = currentLikeCount)))
            }

            // Publisher info for viewers
            if (!isStreamer) {
                val publisherId = commands.hget(pubKey, "userId")
                publisherId?.let {
                    send(Frame.Text(WS_JSON.encodeToString(PublisherInfoEvent(userId = it))))
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
                            val msg = WS_JSON.decodeFromString<ChatMessageCommand>(text)
                            commands.publish(chatCh, WS_JSON.encodeToString(
                                ChatMessageEvent(userId = msg.userId, username = msg.username, message = msg.message)
                            ))
                        }

                        "like" -> {
                            val newCount = commands.incr(lCountKey).toInt()
                            commands.publish(likeCh, WS_JSON.encodeToString(
                                LikeUpdateEvent(newCount = newCount)
                            ))
                        }

                        "grant_moderator" -> {
                            val cmd = WS_JSON.decodeFromString<GrantModeratorCommand>(text)
                            val actorRole = commands.hget(rolesRedisKey, userId) ?: "viewer"
                            val targetRole = commands.hget(rolesRedisKey, cmd.targetUserId) ?: "viewer"
                            val username = userSchema.findById(cmd.targetUserId.toInt())?.username ?: "Unknown"

                            if (rank(actorRole) > rank(targetRole)) {
                                // 1. Update Redis
                                commands.hset(rolesRedisKey, cmd.targetUserId, "moderator")

                                // 2. Send confirmation to grantor (actor)
                                streamSessionsMap[streamId]
                                    ?.filter { it.userId == userId }
                                    ?.forEach { session ->
                                        session.wsSession.send(
                                            Frame.Text(WS_JSON.encodeToString(
                                                ModeratorActionSuccessEvent(
                                                    action = "grant",
                                                    targetUserId = cmd.targetUserId,
                                                    targetUsername = username,
                                                )
                                            ))
                                        )
                                }

                                // 3. Notify target user
                                streamSessionsMap[streamId]
                                    ?.filter { it.userId == cmd.targetUserId }
                                    ?.forEach { session ->
                                        session.wsSession.send(
                                            Frame.Text(WS_JSON.encodeToString(
                                                ModeratorGrantedEvent(targetUserId = cmd.targetUserId)
                                            ))
                                        )
                                    }
                            }
                        }

                        "revoke_moderator" -> {
                            val cmd = WS_JSON.decodeFromString<RevokeModeratorCommand>(text)
                            val actorRole = commands.hget(rolesRedisKey, userId) ?: "viewer"
                            val targetRole = commands.hget(rolesRedisKey, cmd.targetUserId) ?: "viewer"
                            val username = userSchema.findById(cmd.targetUserId.toInt())?.username ?: "Unknown"

                            if (rank(actorRole) > rank(targetRole)) {
                                // 1. Update Redis
                                commands.hdel(rolesRedisKey, cmd.targetUserId)

                                // 2. Send confirmation to grantor (actor)
                                streamSessionsMap[streamId]
                                    ?.filter { it.userId == userId }
                                    ?.forEach { session ->
                                        session.wsSession.send(
                                            Frame.Text(WS_JSON.encodeToString(
                                                ModeratorActionSuccessEvent(
                                                    action = "revoke",
                                                    targetUserId = cmd.targetUserId,
                                                    targetUsername = username,
                                                )
                                            ))
                                        )
                                    }

                                // 3. Notify target user
                                streamSessionsMap[streamId]
                                    ?.filter { it.userId == cmd.targetUserId }
                                    ?.forEach { session ->
                                        session.wsSession.send(
                                            Frame.Text(WS_JSON.encodeToString(
                                                ModeratorRevokedEvent(targetUserId = cmd.targetUserId)
                                            ))
                                        )
                                    }
                            }
                        }

                        "kick_user" -> {
                            val cmd = WS_JSON.decodeFromString<KickUserCommand>(text)
                            val actorRole = commands.hget(rolesRedisKey, userId) ?: "viewer"
                            val targetRole = commands.hget(rolesRedisKey, cmd.targetUserId) ?: "viewer"
                            val username = userSchema.findById(cmd.targetUserId.toInt())?.username ?: "Unknown"

                            if (rank(actorRole) > rank(targetRole)) {
                                // 1. Confirm to ALL actor sessions
                                streamSessionsMap[streamId]
                                    ?.filter { it.userId == userId } // Actor's sessions
                                    ?.forEach { session ->
                                        session.wsSession.send(
                                            Frame.Text(WS_JSON.encodeToString(
                                                ModeratorActionSuccessEvent(
                                                    action = "kick",
                                                    targetUserId = cmd.targetUserId,
                                                    targetUsername = username
                                                )
                                            ))
                                        )
                                    }

                                // 2. Notify ALL target sessions and close them
                                streamSessionsMap[streamId]
                                    ?.filter { it.userId == cmd.targetUserId } // Target's sessions
                                    ?.forEach { session ->
                                        launch {
                                            session.wsSession.send(
                                                Frame.Text(WS_JSON.encodeToString(
                                                    UserKickedEvent(
                                                        targetUserId = cmd.targetUserId,
                                                        reason = "Kicked by moderator"
                                                    )
                                                ))
                                            )
                                            session.wsSession.close()
                                        }
                                    }
                            }
                        }

                        "mute_user" -> {
                            val cmd = WS_JSON.decodeFromString<MuteUserCommand>(text)
                            val actorRole = commands.hget(rolesRedisKey, userId) ?: "viewer"
                            val targetRole = commands.hget(rolesRedisKey, cmd.targetUserId) ?: "viewer"
                            val username = userSchema.findById(cmd.targetUserId.toInt())?.username ?: "Unknown"

                            if (rank(actorRole) > rank(targetRole)) {
                                // 1. Set mute in Redis
                                commands.setex("muted:${cmd.targetUserId}", cmd.durationMs / 1000, "1")

                                // 2. Confirm to actor
                                streamSessionsMap[streamId]
                                    ?.filter { it.userId == userId }
                                    ?.forEach { session ->
                                        session.wsSession.send(
                                            Frame.Text(WS_JSON.encodeToString(
                                                ModeratorActionSuccessEvent(
                                                    action = "mute",
                                                    targetUserId = cmd.targetUserId,
                                                    targetUsername = username,
                                                    durationMs = cmd.durationMs
                                                )
                                            ))
                                        )
                                    }

                                // 3. Notify target
                                streamSessionsMap[streamId]
                                    ?.filter { it.userId == cmd.targetUserId }
                                    ?.forEach { session ->
                                        session.wsSession.send(
                                            Frame.Text(WS_JSON.encodeToString(
                                                UserMutedEvent(
                                                    targetUserId = cmd.targetUserId,
                                                    durationMs = cmd.durationMs
                                                )
                                            ))
                                        )
                                    }
                            }
                        }

                        "unmute_user" -> {
                            val cmd = WS_JSON.decodeFromString<UnmuteUserCommand>(text)
                            val actorRole = commands.hget(rolesRedisKey, userId) ?: "viewer"
                            val targetRole = commands.hget(rolesRedisKey, cmd.targetUserId) ?: "viewer"
                            val username = userSchema.findById(cmd.targetUserId.toInt())?.username ?: "Unknown"

                            if (rank(actorRole) > rank(targetRole)) {
                                // 1. Remove mute
                                commands.del("muted:${cmd.targetUserId}")

                                // 1. Confirm to ALL actor sessions
                                streamSessionsMap[streamId]
                                    ?.filter { it.userId == userId } // Actor's sessions
                                    ?.forEach { session ->
                                        session.wsSession.send(
                                            Frame.Text(WS_JSON.encodeToString(
                                                ModeratorActionSuccessEvent(
                                                    action = "unmute",
                                                    targetUserId = cmd.targetUserId,
                                                    targetUsername = username
                                                )
                                            ))
                                        )
                                    }

                                // 2. Notify ALL target sessions
                                streamSessionsMap[streamId]
                                    ?.filter { it.userId == cmd.targetUserId } // Target's sessions
                                    ?.forEach { session ->
                                        session.wsSession.send(
                                            Frame.Text(WS_JSON.encodeToString(
                                                UserUnmutedEvent(targetUserId = cmd.targetUserId)
                                            ))
                                        )
                                    }
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
                        VisitorCountEvent(currentCount = updated)
                    ))
                } else {
                    // Streamer cleanup
                    commands.set(visCountKey, "0")
                    commands.set(lCountKey, "0")
                    commands.del(pubKey)

                    // Notify all visitors first
                    val dead = mutableListOf<UserSession>()
                    sessions.forEach { session ->
                        try {
                            if (session.userId != userId) { // Don't disconnect ourselves
                                session.wsSession.send(Frame.Text(WS_JSON.encodeToString(PublisherDisconnectedEvent)))
                                session.wsSession.close()
                                dead.add(session)
                            }
                        } catch (e: Exception) {
                            dead.add(session)
                        }
                    }
                    removeDeadSessions(sessions, dead)

                    commands.publish(visCh, WS_JSON.encodeToString(VisitorCountEvent(currentCount = 0)))
                    commands.publish(likeCh, WS_JSON.encodeToString(LikeUpdateEvent(newCount =0)))
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