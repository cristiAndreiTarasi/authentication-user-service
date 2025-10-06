package example.com.routes

import example.com.PartDataItems
import example.com.UserRole
import example.com.routes.dtos.CreateStreamRequestDto
import example.com.routes.dtos.CreateStreamResponseDto
import example.com.routes.dtos.DeleteStreamResponse
import example.com.routes.dtos.StreamDto
import example.com.routes.dtos.StreamResponseDto
import example.com.routes.dtos.StreamSummaryDto
import example.com.schemas.StreamSchema
import example.com.schemas.UserSchema
import example.com.services.gridfs.GridFSService
import example.com.services.token.ITokenService
import example.com.services.token.TokenClaim
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toKotlinLocalDateTime
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import net.coobird.thumbnailator.Thumbnails
import org.bson.types.ObjectId
import java.time.LocalDateTime
import java.time.format.DateTimeParseException
import java.util.UUID

fun Route.streamRoutes(
    streamSchema: StreamSchema,
    gridFSService: GridFSService,
    publishTokenService: ITokenService,
    userSchema: UserSchema,
) {
    authenticate("auth-jwt") {
        // Route to get a specific stream by ID
        get("/streams/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, "Invalid stream ID")
                return@get
            }

            val stream = streamSchema.findById(id)
            if (stream == null) {
                call.respond(HttpStatusCode.NotFound, "Stream not found")
            } else {
                call.respond(HttpStatusCode.OK, stream.toStreamResponse())
            }
        }

        // Route to get all streams
        get("/streams") {
            val cursorParam = call.parameters["cursor"]
            val limit = call.parameters["limit"]?.toIntOrNull() ?: 10

            val cursor: LocalDateTime? = cursorParam?.let {
                try {
                    LocalDateTime.parse(it)
                } catch (exc: DateTimeParseException) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid cursor format!")
                    return@get
                }
            }

            val streams = streamSchema.fetchStreamsCursor(cursor?.toKotlinLocalDateTime(), limit)

            call.respond(HttpStatusCode.OK, streams.map { it.toStreamResponse() })
        }

        // public route: returns list of stream summaries
        get("/streams/live") {
            val limit = call.parameters["limit"]?.toIntOrNull() ?: 10
            val streams = streamSchema.fetchLiveStreams(limit)
            // Map to lightweight DTO
            val summaries = streams.map { s ->
                val id = s.id ?: return@map null // defensive: skip if null
                val thumbnailPath = s.thumbnailId?.let { "/streams/$id/thumbnail" }
                StreamSummaryDto(
                    id = id,
                    title = s.title,
                    userId = s.userId,
                    username = s.username,
                    thumbnailPath = thumbnailPath,
                    startsAt = s.startsAt,
                    createdAt = s.createdAt
                )
            }.filterNotNull()
            call.respond(HttpStatusCode.OK, summaries)
        }


        // Route to get streams by category
        get("/streams/category/{categoryId}") {
            val page = call.parameters["page"]?.toIntOrNull() ?: 1
            val pageSize = call.parameters["pageSize"]?.toIntOrNull() ?: 10
            val categoryId = call.parameters["categoryId"]?.toIntOrNull()

            if (categoryId == null) {
                call.respond(HttpStatusCode.BadRequest, "Invalid category ID")
                return@get
            }

            val streams = streamSchema.fetchStreamsByCategory(categoryId, page, pageSize)
            call.respond(HttpStatusCode.OK, streams.map { it.toStreamResponse() })
        }

        // Route to get streams by tag
        get("/streams/tag/{tag}") {
            val page = call.parameters["page"]?.toIntOrNull() ?: 1
            val pageSize = call.parameters["pageSize"]?.toIntOrNull() ?: 10
            val tag = call.parameters["tag"]

            if (tag == null) {
                call.respond(HttpStatusCode.BadRequest, "Tag is required")
                return@get
            }

            val streams = streamSchema.fetchStreamsByTag(tag, page, pageSize)
            call.respond(HttpStatusCode.OK, streams.map { it.toStreamResponse() })
        }

        // Put this near avatar route; public is fine
        get("/streams/{streamId}/thumbnail") {
            val streamId = call.parameters["streamId"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Stream ID is missing")

            val stream = streamSchema.findById(streamId)
                ?: return@get call.respond(HttpStatusCode.NotFound, "Stream not found")

            val thumbnailId = stream.thumbnailId ?: return@get call.respond(HttpStatusCode.NotFound, "Thumbnail not found")
            val imageBytes = try {
                gridFSService.fetchImage(ObjectId(thumbnailId))
            } catch (e: IllegalArgumentException) {
                return@get call.respond(HttpStatusCode.BadRequest, "Invalid thumbnail id")
            }

            if (imageBytes.isNotEmpty()) {
                call.respondBytes(imageBytes, ContentType.Image.JPEG)
            } else {
                call.respond(HttpStatusCode.NotFound, "Thumbnail not found")
            }
        }

        post("/streams/start") {
            val principal = call.principal<JWTPrincipal>()
            val role = principal?.payload?.getClaim("role")?.asString()

            if (role != UserRole.OWNER.roleName) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    CreateStreamResponseDto(message = "You do not have access to this resource.")
                )
                return@post
            }

            // receive multipart
            val multipart = call.receiveMultipart()
            var streamMetaData: CreateStreamRequestDto? = null
            var thumbnailContent: ByteArray? = null

            multipart.forEachPart { part ->
                when (part) {
                    is PartData.FormItem -> {
                        if (part.name == PartDataItems.METADATA.displayName) {
                            streamMetaData = Json.decodeFromString(
                                CreateStreamRequestDto.serializer(),
                                part.value
                            )
                        }
                    }
                    is PartData.FileItem -> {
                        if (part.name == PartDataItems.THUMBNAIL.displayName) {
                            thumbnailContent = part.streamProvider().readBytes()
                        }
                    }
                    else -> part.dispose()
                }
                part.dispose()
            }

            if (streamMetaData == null) {
                call.respond(HttpStatusCode.BadRequest, "Stream metadata is missing.")
                return@post
            }

            // Basic validations
            if (streamMetaData!!.title.length > 255) {
                call.respond(HttpStatusCode.BadRequest, "Title must be 255 characters or less")
                return@post
            }
            if (streamMetaData!!.description != null && streamMetaData!!.description!!.length > 255) {
                call.respond(HttpStatusCode.BadRequest, "Description must be 255 characters or less")
                return@post
            }

            // Upload thumbnail if present
            val thumbnailId: String? = thumbnailContent?.let { rawImage ->
                gridFSService.uploadImage(
                    streamMetaData!!.userId,
                    rawImage,
                    transformation = { data ->
                        val inputStream = java.io.ByteArrayInputStream(data)
                        val outputStream = java.io.ByteArrayOutputStream()

                        Thumbnails.of(inputStream)
                            .size(180, 320)
                            .keepAspectRatio(true)
                            .outputFormat("jpg")
                            .outputQuality(0.8)
                            .toOutputStream(outputStream)

                        outputStream.toByteArray()
                    }
                ).toHexString()
            }

            // Use UTC for created_at to keep a canonical absolute time in DB
            val createdAtUtc = Clock.System.now().toLocalDateTime(TimeZone.UTC)

            // Validate user exists
            val user = userSchema.findById(streamMetaData!!.userId)
            if (user == null) {
                call.respond(HttpStatusCode.BadRequest, "User not found.")
                return@post
            }

            // Build StreamDto with createdAt in UTC (keep startsAt as provided; consider converting client-provided startsAt to UTC if needed)
            val stream = StreamDto(
                title = streamMetaData!!.title,
                description = streamMetaData!!.description,
                userId = streamMetaData!!.userId,
                username = user.username,
                privacyType = streamMetaData!!.privacyType,
                ticketPrice = streamMetaData!!.ticketPrice,
                categories = streamMetaData!!.categories,
                tags = streamMetaData!!.tags,
                thumbnailId = thumbnailId,
                startsAt = streamMetaData!!.startsAt, // if client sends local wall-time, consider changing API to accept ISO Instants
                createdAt = createdAtUtc
            )

            val streamId = streamSchema.create(stream)
            val streamKey = UUID.randomUUID().toString()
            val jti = UUID.randomUUID().toString()

            val claims = listOf(
                TokenClaim("userId", streamMetaData!!.userId.toString()),
                TokenClaim("streamKey", streamKey),
                TokenClaim("jti", jti)
            )

            // generate token + expiry in one call
            val generated = publishTokenService.generateAccessTokenWithExpiry(claims, streamMetaData!!.timezoneId)
            val publishToken = generated.token
            val expiresAtInstant = generated.expiresAt // kotlinx.datetime.Instant

            val persisted = try {
                streamSchema.setPublishInfo(streamId, streamKey, jti, expiresAtInstant)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, "Failed to persist publish info")
                return@post
            }

            if (!persisted) {
                // optional: delete stream row to avoid an orphan (or mark), here we just return failure
                call.respond(HttpStatusCode.InternalServerError, "Failed to persist publish info")
                return@post
            }

            // 4) Return response: streamId, streamKey and publishToken and canonical expiry ISO instant
            call.respond(
                HttpStatusCode.Created,
                CreateStreamResponseDto(
                    streamId = streamId,
                    streamKey = streamKey,
                    publishToken = publishToken,
                    // return an unambiguous instant string in UTC
                    expiresAt = expiresAtInstant.toString(),
                    isLive = false
                )
            )
        }

        delete("/streams/delete/{streamId}") {
            val principal = call.principal<JWTPrincipal>()
            val role = principal?.payload?.getClaim("role")?.asString()
            val userId = principal?.payload?.getClaim("userId")?.asInt()

            if (role == UserRole.OWNER.roleName) {
                val streamId = call.parameters["streamId"]?.toIntOrNull()
                if (streamId == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        DeleteStreamResponse("Invalid stream ID")
                    )
                    return@delete
                }

                val stream = streamSchema.findById(streamId)
                if (stream?.thumbnailId != null) {
                    gridFSService.deleteImage(ObjectId(stream.thumbnailId))
                }

//                val user = userId?.let { it1 -> userSchema.findById(it1) }
//
//                if (user == null) {
//                    call.respond(HttpStatusCode.BadRequest, "User not found.")
//                    return@delete
//                }

                val isDeleted = streamSchema.delete(streamId)
                if (isDeleted) {
                    call.respond(
                        HttpStatusCode.OK,
                        DeleteStreamResponse(
                            message = "Stream deleted successfully",
                            isLive = false
                        )
                    )
                } else {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        DeleteStreamResponse("Failed to delete stream")
                    )
                }
            } else {
                call.respond(
                    HttpStatusCode.Forbidden,
                    DeleteStreamResponse("You do not have access to this resource.")
                )
            }
        }
    }
}

// Extension function to convert Stream to StreamResponse DTO
fun StreamDto.toStreamResponse(): StreamResponseDto {
    return StreamResponseDto(
        id = id,
        title = title,
        description = description,
        userId = userId,
        username = username,
        privacyType = privacyType,
        ticketPrice = ticketPrice,
        categories = categories,
        tags = tags,
        createdAt = createdAt,
        thumbnailId = thumbnailId,
        thumbnailData = thumbnailData,
        streamKey = streamKey,
        status = status.dbValue
    )
}










