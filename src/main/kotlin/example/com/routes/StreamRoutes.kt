package example.com.routes

import example.com.PartDataItems
import example.com.UserRole
import example.com.routes.dtos.CreateStreamRequest
import example.com.routes.dtos.CreateStreamResponse
import example.com.routes.dtos.DeleteStreamResponse
import example.com.routes.dtos.StreamResponseDto
import example.com.routes.dtos.StreamDto
import example.com.schemas.StreamSchema
import example.com.schemas.UserSchema
import example.com.services.gridfs.GridFSService
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
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toKotlinLocalDateTime
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import net.coobird.thumbnailator.Thumbnails
import org.bson.types.ObjectId
import org.litote.kmongo.currentDate
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeParseException
import java.util.Base64

fun Route.streamRoutes(
    streamSchema: StreamSchema,
    gridFSService: GridFSService,
    userSchema: UserSchema
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

        post("/streams/start") {
            val principal = call.principal<JWTPrincipal>()
            val role = principal?.payload?.getClaim("role")?.asString()

            if (role == UserRole.OWNER.roleName) {
                val multipartData = call.receiveMultipart()

                var streamMetaData: CreateStreamRequest? = null
                var thumbnailContent: ByteArray? = null

                multipartData.forEachPart { part ->
                    when (part) {
                        is PartData.FormItem -> {
                            if (part.name == PartDataItems.METADATA.displayName) {
                                // Deserialize the metadata from JSON string
                                streamMetaData = Json.decodeFromString(
                                    CreateStreamRequest.serializer(),
                                    part.value
                                )
                            }
                        }

                        is PartData.FileItem -> {
                            if (part.name == PartDataItems.THUMBNAIL.displayName) {
                                // Process the thumbnail image file
                                thumbnailContent = part.streamProvider().readBytes()
                            }
                        }

                        else -> part.dispose()
                    }
                }

                if (streamMetaData == null) {
                    call.respond(HttpStatusCode.BadRequest, "Stream metadata is missing.")
                    return@post
                }

                if (streamMetaData!!.title.length > 255) {
                    call.respond(HttpStatusCode.BadRequest, "Title must be 255 characters or less")
                    return@post
                }

                if (streamMetaData!!.description != null && streamMetaData!!.description!!.length > 255) {
                    call.respond(HttpStatusCode.BadRequest, "Description must be 255 characters or less")
                    return@post
                }

                // If there's an image, upload it and get the thumbnailId
                val thumbnailId: String? = thumbnailContent?.let { rawImage ->
                    gridFSService.uploadImage(
                        streamMetaData!!.userId,
                        rawImage,
                        transformation = { data ->
                            val inputStream = ByteArrayInputStream(data)
                            val outputStream = ByteArrayOutputStream()

                            Thumbnails.of(inputStream)
                                .size(180, 320)
                                .keepAspectRatio(true)
                                .outputFormat("jpg")
                                .outputQuality(0.8)
                                .toOutputStream(outputStream)

                            // Return the transformed image as a ByteArray.
                            outputStream.toByteArray()
                        }
                    ).toHexString()
                }

                val timezone = TimeZone.of(streamMetaData!!.timezoneId)

                val user = userSchema.findById(streamMetaData!!.userId)
                if (user == null) {
                    call.respond(HttpStatusCode.BadRequest, "User not found.")
                    return@post
                }

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
                    startsAt = streamMetaData!!.startsAt,
                    createdAt = Clock.System.now().toLocalDateTime(timezone),
                )

                val streamId = streamSchema.create(stream)
                call.respond(
                    HttpStatusCode.Created,
                    CreateStreamResponse(
                        streamId = streamId,
                        isLive = user.isLive
                    )
                )
            } else {
                call.respond(
                    HttpStatusCode.Forbidden,
                    CreateStreamResponse(
                        message = "You do not have access to this resource.",
                    )
                )
            }
        }

        // Route to delete a stream
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
        thumbnailData = thumbnailData
    )
}
