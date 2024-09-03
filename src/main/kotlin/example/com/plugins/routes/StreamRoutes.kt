package example.com.plugins.routes

import example.com.schemas.StreamDataModel
import example.com.schemas.StreamSchema
import example.com.schemas.TokenSchema
import example.com.schemas.UserSchema
import example.com.services.hashing.HashingService
import example.com.services.token.TokenService
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.*
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bson.types.ObjectId

@Serializable
data class CreateStreamRequest(
    val title: String,
    val description: String,
    val userId: Int,
    val startTime: String,
    // Option 1: Set a default end time (e.g., 1 hour after start) if not provided.
    // Option 2: Make endTime nullable and handle it only if explicitly set by the client.
    val endTime: String? = null,
    val isPublic: Boolean,
    val isTicketed: Boolean,
    val categories: List<CategoryDto>,
    val tags: List<String>,
    val createdAt: LocalDateTime,
    val thumbnailId: String? = null // ID of the uploaded thumbnail in GridFS
)

@Serializable
data class CategoryDto(
    val id: Int,
    val name: String
)

@Serializable
data class StreamResponse(
    val title: String,
    val description: String,
    val userId: Int,
    val startTime: String?,
    val endTime: String? = null,
    val isPublic: Boolean,
    val isTicketed: Boolean,
    val categories: List<CategoryDto>,
    val tags: List<String>,
    val createdAt: LocalDateTime,
    val thumbnailId: String? = null
)

@Serializable
data class UpdateStreamRequest(
    val title: String,
    val description: String,
    val startTime: LocalDateTime? = null,
    val endTime: LocalDateTime? = null,
    val isPublic: Boolean,
    val isTicketed: Boolean,
    val categoryId: Int
)

fun Route.streamRoutes(
    streamSchema: StreamSchema,
    userSchema: UserSchema
) {
    authenticate("auth-jwt") {
        // Route to start a new stream
        post("/streams/start") {
            val multipartData = call.receiveMultipart()

            var createStreamRequest: CreateStreamRequest? = null
            var thumbnailContent: ByteArray? = null

            multipartData.forEachPart { part ->
                when (part) {
                    is PartData.FormItem -> {
                        if (part.name === "metadata") {
                            // Deserialize the metadata from JSON string
                            createStreamRequest = Json.decodeFromString(
                                CreateStreamRequest.serializer(),
                                part.value
                            )
                        }
                    }

                    is PartData.FileItem -> {
                        if (part.name === "thumbnail") {
                            // Process the thumbnail image file
                            thumbnailContent = part.streamProvider().readBytes()
                        }
                    }

                    else -> part.dispose()
                }
            }

            if (createStreamRequest == null) {
                call.respond(HttpStatusCode.BadRequest, "Stream metadata is missing.")
                return@post
            }

            // If there's an image, upload it and get the thumbnailId
            val thumbnailId: String? = thumbnailContent?.let {
                userSchema.uploadImage(createStreamRequest!!.userId, it).toHexString()
            }

            val stream = StreamDataModel(
                title = createStreamRequest!!.title,
                description = createStreamRequest!!.description,
                userId = createStreamRequest!!.userId,
                startTime = createStreamRequest!!.startTime,
                endTime = createStreamRequest!!.endTime,
                isPublic = createStreamRequest!!.isPublic,
                isTicketed = createStreamRequest!!.isTicketed,
                categories = createStreamRequest!!.categories,
                tags = createStreamRequest!!.tags,
                createdAt = createStreamRequest!!.createdAt,
                thumbnailId = createStreamRequest!!.thumbnailId
            )

            val streamId = streamSchema.create(stream)
            call.respond(HttpStatusCode.Created, streamId)
        }

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

        // Route to delete a stream
        delete("/streams/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, "Invalid stream ID")
                return@delete
            }

            val stream = streamSchema.findById(id)
            if (stream?.thumbnailId != null) {
                // Delete the thumbnail if exists
                userSchema.deleteImage(ObjectId(stream.thumbnailId))
            }

            val isDeleted = streamSchema.delete(id)
            if (isDeleted) {
                call.respond(HttpStatusCode.OK, "Stream deleted successfully")
            } else {
                call.respond(HttpStatusCode.InternalServerError, "Failed to delete stream")
            }
        }
    }
}

// Extension function to convert Stream to StreamResponse DTO
fun StreamDataModel.toStreamResponse(): StreamResponse {
    return StreamResponse(
        title = title,
        description = description,
        userId = userId,
        startTime = startTime,
        endTime = endTime,
        isPublic = isPublic,
        isTicketed = isTicketed,
        categories = categories,
        tags = tags,
        createdAt = createdAt
    )
}
