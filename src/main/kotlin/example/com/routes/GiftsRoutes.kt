package example.com.routes

import example.com.routes.dtos.LiveEvent
import example.com.routes.dtos.SendGiftRequestDto
import example.com.schemas.GiftsSchema
import example.com.schemas.StreamSchema
import example.com.schemas.UserSchema
import example.com.services.gifts.GiftsService
import example.com.services.gifts.InsufficientFundsException
import example.com.services.gifts.computeCoinsForGift
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

                    // Optionally fetch usernames to put in outbox payload
                    val fromUsername = userSchema.findById(currentUserId)?.username
                    val toUsername = userSchema.findById(targetUserId)?.username

                    // Execute transactional send
                    val result = giftsService.sendGift(
                        idempotencyKey = body.idempotencyKey,
                        fromUserId = currentUserId,
                        toUserId = targetUserId,
                        streamId = streamIdentifier, // keep original identifier (streamKey or numeric string) for outbox
                        giftType = body.giftId,
                        quantity = body.quantity,
                        coinsAmount = coinsAmount,
                        fromUsername = fromUsername,
                        toUsername = toUsername
                    )

                    call.respond(HttpStatusCode.Created, result)

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

                    // Broadcast typed gift (will route to billing/social etc and local sessions)
                    crossInstanceBroadcaster.broadcastToRoom(streamIdentifier, giftEvent)

                    // Build friendly system message text for chat
                    val giftName = try {
                        giftsSchema.getGiftById(body.giftId)?.name ?: body.giftId
                    } catch (e: Exception) { body.giftId }

                    val usernameForMsg = fromUsername ?: "A viewer"
                    val systemText = "$usernameForMsg sent $giftName"

                    val systemMessage = LiveEvent.SystemMessage(
                        roomId = streamIdentifier,
                        text = systemText,
                        timestamp = System.currentTimeMillis()
                    )

                    // Broadcast chat-visible system message
                    crossInstanceBroadcaster.broadcastToRoom(streamIdentifier, systemMessage)
                } catch (e: InsufficientFundsException) {
                    call.respond(HttpStatusCode.PaymentRequired, mapOf("error" to "insufficient_funds", "required" to e.required, "balance" to e.balance))
                } catch (e: Exception) {
                    call.application.environment.log.error("Failed to send gift", e)
                    call.respond(HttpStatusCode.InternalServerError, "Failed to send gift")
                }
            }
        }
    }
}


