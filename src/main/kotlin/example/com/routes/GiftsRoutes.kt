package example.com.routes

import example.com.routes.dtos.LiveEvent
import example.com.routes.dtos.SendGiftRequestDto
import example.com.schemas.GiftsSchema
import example.com.schemas.StreamSchema
import example.com.schemas.UserSchema
import example.com.services.gifts.GiftsService
import example.com.services.gifts.InsufficientFundsException
import example.com.services.gifts.computeCoinsForGift
import example.com.services.redis.RedisStreams
import example.com.services.redis.ShardedRedisService
import example.com.services.token.ITokenService
import example.com.services.ws_session.CrossInstanceBroadcaster
import example.com.services.ws_session.DistributedPermissionManager
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receiveOrNull
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.giftsRoutes(
    giftsSchema: GiftsSchema,
    giftsService: GiftsService,
    authTokenService: ITokenService,
    streamSchema: StreamSchema,
    shardedRedisService: ShardedRedisService,
    userSchema: UserSchema,
    distributedPermissionManager: DistributedPermissionManager,
    crossInstanceBroadcaster: CrossInstanceBroadcaster
) {
    route("/gifts") {
        get {
            try {
                println("Fetching gifts catalog...")
                val catalog = giftsSchema.getCatalog()
                println("Successfully fetched ${catalog.size} gifts")
                call.respond(HttpStatusCode.OK, catalog)
            } catch (e: Exception) {
                call.application.environment.log.error("Failed to fetch gifts catalog", e)
                println("ERROR in getCatalog: ${e.message}")
                e.printStackTrace()
                call.respond(HttpStatusCode.InternalServerError, "Failed to fetch gifts: ${e.message}")
            }
        }

        authenticate("auth-jwt") {
            post("/send/{streamId}") {
                val principal = call.principal<JWTPrincipal>() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val idStr = authTokenService.getClaim(principal, "userId") ?: authTokenService.getClaim(principal, "sub")
                val currentUserId = idStr?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.Unauthorized)

                val streamIdentifier = call.parameters["streamId"] ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing streamId/streamKey")
                val body = call.receiveOrNull<SendGiftRequestDto>() ?: return@post call.respond(HttpStatusCode.BadRequest, "Malformed body")

                try {
                    // First attempt: canonical Redis lookup (works for both numeric and streamKey)
                    val ownerStrFromRedis = try {
                        distributedPermissionManager.getStreamOwner(streamIdentifier)
                    } catch (e: Exception) {
                        null
                    }

                    var targetUserId: Int? = ownerStrFromRedis?.toIntOrNull()

                    // If Redis didn't yield an owner, try to resolve using mapping or DB fallback
                    if (targetUserId == null) {
                        // If streamIdentifier is a streamKey, try mapping "streamKey:<streamKey>:streamId" -> numeric id
                        val mappedStreamIdStr = try { shardedRedisService.get("streamKey:$streamIdentifier:streamId") } catch (e: Exception) { null }
                        val mappedStreamId = mappedStreamIdStr?.toIntOrNull()

                        if (mappedStreamId != null) {
                            // mapped numeric id -> try redis owner for numeric or fall back to DB
                            val ownerForMapped = try { distributedPermissionManager.getStreamOwner(mappedStreamId.toString()) } catch (_: Exception) { null }
                            targetUserId = ownerForMapped?.toIntOrNull()
                            if (targetUserId == null) {
                                // fallback to DB stream row
                                val streamRow = streamSchema.findById(mappedStreamId)
                                targetUserId = streamRow?.userId
                            }
                        } else {
                            // if provided param was numeric — try DB stream row
                            val numericParam = streamIdentifier.toIntOrNull()
                            if (numericParam != null) {
                                // try redis first for numeric param (in case owner was set there)
                                val ownerForNumeric = try { distributedPermissionManager.getStreamOwner(numericParam.toString()) } catch (_: Exception) { null }
                                targetUserId = ownerForNumeric?.toIntOrNull()

                                if (targetUserId == null) {
                                    val streamRow = streamSchema.findById(numericParam)
                                    targetUserId = streamRow?.userId
                                }
                            }
                        }
                    }

                    if (targetUserId == null) {
                        call.respond(HttpStatusCode.NotFound, "Stream owner not found")
                        return@post
                    }

                    // compute coins amount from catalog
                    val coinsAmount = try {
                        computeCoinsForGift(body.giftId, body.quantity, giftsSchema)
                    } catch (e: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, "Unknown gift")
                        return@post
                    }

                    // Fetch gift details for structured message
                    val gift = giftsSchema.getGiftById(body.giftId)
                    val giftName = gift?.name ?: body.giftId

                    // Fetch usernames
                    val fromUsername = userSchema.findById(currentUserId)?.username
                    val toUsername = userSchema.findById(targetUserId)?.username

                    // Execute transactional send
                    val result = giftsService.sendGift(
                        idempotencyKey = body.idempotencyKey,
                        fromUserId = currentUserId,
                        toUserId = targetUserId,
                        streamId = streamIdentifier,
                        giftType = body.giftId,
                        quantity = body.quantity,
                        coinsAmount = coinsAmount,
                        fromUsername = fromUsername,
                        toUsername = toUsername
                    )

                    call.respond(HttpStatusCode.Created, result)

                    // Always create the Gift event for counters and possible animation
                    val giftEvent = LiveEvent.Gift(
                        giftTxId = result.giftTxId,
                        idempotency_key = body.idempotencyKey,
                        roomId = streamIdentifier,
                        initiatorId = currentUserId.toString(),
                        timestamp = System.currentTimeMillis(),
                        giftId = body.giftId,
                        quantity = body.quantity,
                        value = coinsAmount.toDouble()
                    )

                    val usernameForMsg = fromUsername ?: "A viewer"
                    val isExpensive = coinsAmount >= 1000

                    // For expensive gifts (1000+ coins): send Gift event for animation area
                    if (isExpensive) {
                        // Broadcast typed gift event (for animation area)
                        crossInstanceBroadcaster.broadcastToRoom(streamIdentifier, giftEvent)

                        // For expensive gifts, send regular system message (no special styling)
                        val systemMessage = LiveEvent.SystemMessage(
                            roomId = streamIdentifier,
                            text = "$usernameForMsg sent $giftName",
                            messageType = "generic", // NOT "gift" for expensive gifts
                            metadata = mapOf(
                                "isExpensive" to "true",
                                "totalCoins" to coinsAmount.toString()
                            ),
                            timestamp = System.currentTimeMillis()
                        )

                        // Broadcast regular system message for chat
                        crossInstanceBroadcaster.broadcastToRoom(streamIdentifier, systemMessage)
                    } else {
                        // For cheap gifts (<1000 coins): ONLY send GiftSystemMessage for special styling
                        // DO NOT send a SystemMessage to avoid duplicates
                        val giftSystemMessage = LiveEvent.GiftSystemMessage(
                            roomId = streamIdentifier,
                            text = "$usernameForMsg sent $giftName",
                            giftId = body.giftId,
                            giftName = giftName,
                            giftImageUrl = gift?.imageUrl,
                            quantity = body.quantity,
                            totalCoins = coinsAmount,
                            senderId = currentUserId.toString(),
                            senderName = usernameForMsg,
                            timestamp = System.currentTimeMillis()
                        )

                        // Broadcast structured gift message (for cheap gifts with special styling)
                        crossInstanceBroadcaster.broadcastToRoom(streamIdentifier, giftSystemMessage)

                        // DO NOT send SystemMessage for cheap gifts - that's what causes duplicates
                        // The GiftSystemMessage will be handled by the client as a special chat message
                    }

                    // Update gift counter in Redis
                    try {
                        shardedRedisService.incrementCounter(
                            "${RedisStreams.ROOM_COUNTERS_PREFIX}$streamIdentifier:gifts_total",
                            coinsAmount
                        )
                    } catch (e: Exception) {
                        println("WARN: Failed to update gift counter: ${e.message}")
                    }

                } catch (e: InsufficientFundsException) {
                    call.respond(HttpStatusCode.PaymentRequired, mapOf(
                        "error" to "insufficient_funds",
                        "required" to e.required,
                        "balance" to e.balance
                    ))
                } catch (e: Exception) {
                    call.application.environment.log.error("Failed to send gift", e)
                    call.respond(HttpStatusCode.InternalServerError, "Failed to send gift")
                }
            }
        }
    }
}


