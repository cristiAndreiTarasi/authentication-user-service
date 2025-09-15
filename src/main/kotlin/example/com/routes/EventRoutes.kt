package example.com.routes

import example.com.PartDataItems
import example.com.PrivacyOptions
import example.com.UserRole
import example.com.routes.dtos.CreateEventRequest
import example.com.routes.dtos.CreateStreamResponse
import example.com.routes.dtos.EventDto
import example.com.routes.dtos.EventResponseDto
import example.com.routes.dtos.EventSummaryDto
import example.com.routes.dtos.StreamDto
import example.com.schemas.EventSchema
import example.com.schemas.StreamSchema
import example.com.schemas.UserSchema
import example.com.services.gridfs.GridFSService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.routing.Route
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.*
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import net.coobird.thumbnailator.Thumbnails
import org.bson.types.ObjectId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

fun Route.eventRoutes(
    eventSchema: EventSchema,
    gridFSService: GridFSService,
    userSchema: UserSchema,
    streamSchema: StreamSchema
) {
    authenticate("auth-jwt") {
        // Get event by id
        get("/events/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, "Invalid event id")
                return@get
            }

            val event = eventSchema.findById(id)
            if (event == null) {
                call.respond(HttpStatusCode.NotFound, "Event not found")
            } else {
                /*val thumbnailData = event.thumbnailId?.let {
                    try {
                        val bytes = gridFSService.fetchImage(ObjectId(it))
                        if (bytes.isNotEmpty()) "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes) else null
                    } catch (_: Exception) {
                        null
                    }
                }*/

                val user = userSchema.findById(event.userId)

                call.respond(
                    HttpStatusCode.OK,
                    EventResponseDto(
                        id = event.id!!,
                        title = event.title,
                        description = event.description,
                        userId = event.userId,
                        username = event.username,
                        userOccupation = user?.occupation,
                        userAvatarUrl = "/users/fetch/${event.userId}/avatar",
                        privacyType = event.privacyType,
                        ticketPriceCents = event.ticketPriceCents,
                        categories = event.categories ?: emptyList(),
                        tags = event.tags ?: emptyList(),
                        thumbnailId = event.thumbnailId,
                        thumbnailUrl = "/events/${event.id}/thumbnail",
                        startsAt = event.startsAt,
                        status = event.status,
                        createdAt = event.createdAt,
                        updatedAt = event.updatedAt
                    )
                )
            }
        }

        get("/events/{eventId}/thumbnail") {
            val eventId = call.parameters["eventId"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Event ID is missing")

            // Fetch the image ID associated with the event ID from the database
            val imageIdString = gridFSService.getEventThumbnailIdByEventId(eventId)
                ?: return@get call.respond(HttpStatusCode.NotFound, "Image not found for event")

            val imageId = try {
                ObjectId(imageIdString)
            } catch (e: IllegalArgumentException) {
                return@get call.respond(HttpStatusCode.BadRequest, "Invalid Image ID")
            }

            val imageBytes = gridFSService.fetchImage(imageId)
            if (imageBytes.isNotEmpty()) {
                call.respondBytes(imageBytes, ContentType.Image.JPEG)
            } else {
                call.respond(HttpStatusCode.NotFound, "Image not found")
            }
        }

        get("/events/summary") {
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 6
            val sort = call.request.queryParameters["sort"] ?: "upcoming"

            val events = eventSchema.fetchEventSummaries(limit = limit, sort = sort)

            val response = events.map { eventSummary ->
                EventSummaryDto(
                    id = eventSummary.id,
                    userId = eventSummary.userId,
                    username = eventSummary.username,
                    userAvatarUrl = "/users/fetch/${eventSummary.userId}/avatar",
                    startsAt = eventSummary.startsAt?.toString()
                )
            }

            call.respond(HttpStatusCode.OK, response)
        }

        // Create event (multipart)
        post("/events") {
            val principal = call.principal<JWTPrincipal>()
            if (principal == null) {
                call.respond(HttpStatusCode.Unauthorized, "Missing token")
                return@post
            }

            val role = principal.payload.getClaim("role")?.asString()

            val userIdFromToken = principal.payload.getClaim("userId")?.asInt()
                ?: principal.payload.getClaim("userId")?.asString()?.toIntOrNull()
            if (userIdFromToken == null) {
                call.respond(HttpStatusCode.Unauthorized, "Missing token user id")
                return@post
            }

            // only owners allowed to create events
            if (role != UserRole.OWNER.roleName) {
                call.respond(HttpStatusCode.Forbidden, "Not allowed")
                return@post
            }

            val multipart = call.receiveMultipart()
            var createRequest: CreateEventRequest? = null
            var thumbnailContent: ByteArray? = null

            multipart.forEachPart { part ->
                when (part) {
                    is PartData.FormItem -> {
                        if (part.name == PartDataItems.METADATA.displayName) {
                            createRequest = Json.decodeFromString(CreateEventRequest.serializer(), part.value)
                        }
                    }
                    is PartData.FileItem -> {
                        if (part.name == PartDataItems.THUMBNAIL.displayName) {
                            thumbnailContent = part.streamProvider().readBytes()
                        }
                    }
                    else -> part.dispose()
                }
            }

            if (createRequest == null) {
                call.respond(HttpStatusCode.BadRequest, "Missing metadata")
                return@post
            }

            if (createRequest!!.startsAt.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, "startsAt (scheduled start) is required")
                return@post
            }

            // Upload thumbnail using authoritative owner id
            val thumbnailId: String? = thumbnailContent?.let { raw ->
                gridFSService.uploadImage(
                    userIdFromToken,
                    raw,
                    transformation = { data ->
                        val inputStream = ByteArrayInputStream(data)
                        val outputStream = ByteArrayOutputStream()
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

            val timezone = TimeZone.of(createRequest!!.timezoneId)
            val startsAtLdt = createRequest!!.startsAt?.let { kotlinx.datetime.LocalDateTime.parse(it) }
            val now = Clock.System.now().toLocalDateTime(timezone)

            // Build event using ownerIdFromToken (ignore client-provided userId)
            val eventDto = EventDto(
                title = createRequest!!.title,
                description = createRequest!!.description,
                userId = userIdFromToken,
                username = userSchema.findById(userIdFromToken)?.username,
                privacyType = createRequest!!.privacyType,
                ticketPriceCents = createRequest!!.ticketPriceCents,
                categories = createRequest!!.categories,
                tags = createRequest!!.tags,
                thumbnailId = thumbnailId,
                startsAt = startsAtLdt,
                status = "scheduled",
                createdAt = now,
                updatedAt = now
            )

            val eventId = eventSchema.createEvent(eventDto)
            val created = eventSchema.findById(eventId)!!

            call.respond(
                HttpStatusCode.Created,
                EventResponseDto(
                    id = created.id!!,
                    title = created.title,
                    description = created.description,
                    userId = created.userId,
                    username = created.username,
                    userOccupation = null,
                    userAvatarUrl = created.userAvatarUrl,
                    privacyType = created.privacyType,
                    ticketPriceCents = created.ticketPriceCents,
                    categories = created.categories ?: emptyList(),
                    tags = created.tags ?: emptyList(),
                    thumbnailId = created.thumbnailId,
                    thumbnailUrl = null,
                    startsAt = created.startsAt,
                    status = created.status,
                    createdAt = created.createdAt,
                    updatedAt = created.updatedAt
                )
            )
        }

        get("/users/{id}/events") {
            val userId = call.parameters["id"]?.toIntOrNull()
            if (userId == null) {
                call.respond(HttpStatusCode.BadRequest, "Invalid user id")
                return@get
            }

            try {
                val summaries = eventSchema.fetchEventsByUser(userId)

                val response = summaries.map { s ->
                    EventSummaryDto(
                        id = s.id,
                        userId = s.userId,
                        username = s.username ?: "Unknown",
                        userAvatarUrl = "/users/fetch/${s.userId}/avatar", // relative
                        startsAt = s.startsAt?.toString()
                    )
                }

                call.respond(HttpStatusCode.OK, response)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, "Failed to fetch user events")
            }
        }


        // Start event server-side (server creates a streams row from event metadata)
        post("/events/{id}/start") {
            val principal = call.principal<JWTPrincipal>()
            if (principal == null) {
                call.respond(HttpStatusCode.Unauthorized, "Missing token")
                return@post
            }

            val role = principal.payload.getClaim("role")?.asString()

            val userIdFromToken = principal.payload.getClaim("userId")?.asInt()
                ?: principal.payload.getClaim("userId")?.asString()?.toIntOrNull()
            if (userIdFromToken == null) {
                call.respond(HttpStatusCode.Unauthorized, "Missing token user id")
                return@post
            }

            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, "Invalid event id")
                return@post
            }

            val event = eventSchema.findById(id)
            if (event == null) {
                call.respond(HttpStatusCode.NotFound, "Event not found")
                return@post
            }

            // only owner of event can start (role alone is not enough)
            if (userIdFromToken != event.userId) {
                call.respond(HttpStatusCode.Forbidden, "Not owner of the event")
                return@post
            }

            // map privacy and other fields
            val privacyOption = PrivacyOptions.entries
                .firstOrNull { it.displayName.equals(event.privacyType) }
                ?: PrivacyOptions.PUBLIC

            val usernameNonNull = event.username
                ?: userSchema.findById(event.userId)?.username
                ?: "Unknown"

            val ticketPriceFloat = (event.ticketPriceCents ?: 0L).toFloat() / 100f

            val streamDto = StreamDto(
                title = event.title,
                description = event.description,
                userId = event.userId,
                username = usernameNonNull,
                privacyType = privacyOption,
                ticketPrice = ticketPriceFloat,
                categories = event.categories ?: emptyList(),
                tags = event.tags ?: emptyList(),
                thumbnailId = event.thumbnailId,
                startsAt = event.startsAt,
                createdAt = Clock.System.now().toLocalDateTime(TimeZone.UTC)
            )

            val streamId = streamSchema.create(streamDto)
            eventSchema.updateStatus(id, "published")

            call.respond(
                HttpStatusCode.Created,
                CreateStreamResponse(
                    streamId = streamId,
                    isLive = userSchema.findById(event.userId)?.isLive ?: false
                )
            )
        }
    }
}