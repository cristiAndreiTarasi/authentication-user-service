package example.com.plugins.routes

import example.com.PartDataItems
import example.com.UserRole
import example.com.plugins.routes.dtos.CreateStreamRequest
import example.com.plugins.routes.dtos.CreateStreamResponse
import example.com.plugins.routes.dtos.DeleteStreamResponse
import example.com.plugins.routes.dtos.StreamResponse
import example.com.schemas.StreamDto
import example.com.schemas.StreamSchema
import example.com.schemas.UserSchema
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
import io.ktor.server.routing.*
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import org.bson.types.ObjectId

fun Route.streamRoutes(
    streamSchema: StreamSchema,
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
            val streams = streamSchema.findAll()
            call.respond(HttpStatusCode.OK, streams.map { it.toStreamResponse() })
        }

        // Route to get streams by category
        get("/streams/category/{categoryId}") {
            val categoryId = call.parameters["categoryId"]?.toIntOrNull()
            if (categoryId == null) {
                call.respond(HttpStatusCode.BadRequest, "Invalid category ID")
                return@get
            }

            val streams = streamSchema.findByCategory(categoryId)
            call.respond(HttpStatusCode.OK, streams.map { it.toStreamResponse() })
        }

        // Route to get streams by tag
        get("/streams/tag/{tag}") {
            val tag = call.parameters["tag"]
            if (tag == null) {
                call.respond(HttpStatusCode.BadRequest, "Tag is required")
                return@get
            }

            val streams = streamSchema.findByTag(tag)
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

                // If there's an image, upload it and get the thumbnailId
                val thumbnailId: String? = thumbnailContent?.let {
                    userSchema.uploadImage(streamMetaData!!.userId, it).toHexString()
                }

                val timezone = TimeZone.of(streamMetaData!!.timezoneId)

                val stream = StreamDto(
                    title = streamMetaData!!.title,
                    description = streamMetaData!!.description,
                    userId = streamMetaData!!.userId,
                    privacyType = streamMetaData!!.privacyType,
                    ticketPrice = streamMetaData!!.ticketPrice,
                    categories = streamMetaData!!.categories,
                    tags = streamMetaData!!.tags,
                    thumbnailId = thumbnailId,
                    startsAt = streamMetaData!!.startsAt,
                    createdAt = Clock.System.now().toLocalDateTime(timezone),
                )

                val streamId = streamSchema.create(stream)
                call.respond(HttpStatusCode.Created, CreateStreamResponse(streamId = streamId))
            } else {
                call.respond(
                    HttpStatusCode.Forbidden,
                    CreateStreamResponse(message = "You do not have access to this resource.")
                )
            }
        }

        // Route to delete a stream
        delete("/streams/delete/{streamId}") {
            val principal = call.principal<JWTPrincipal>()
            val role = principal?.payload?.getClaim("role")?.asString()

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
                    userSchema.deleteImage(ObjectId(stream.thumbnailId))
                }

                val isDeleted = streamSchema.delete(streamId)
                if (isDeleted) {
                    call.respond(
                        HttpStatusCode.OK,
                        DeleteStreamResponse("Stream deleted successfully")
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
fun StreamDto.toStreamResponse(): StreamResponse {
    return StreamResponse(
        title = title,
        description = description,
        userId = userId,
        privacyType = privacyType,
        ticketPrice = ticketPrice,
        categories = categories,
        tags = tags,
        createdAt = createdAt,
        thumbnailId = thumbnailId,
    )
}
