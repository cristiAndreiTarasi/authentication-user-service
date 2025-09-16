package example.com.routes

import example.com.routes.dtos.TagCreateRequestDto
import example.com.routes.dtos.TagDto
import example.com.schemas.TagSchema
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*

fun Route.tagRoutes(tagSchema: TagSchema) {
    // Public: list tags (autocomplete / suggestions)
    get("/tags") {
        try {
            val q = call.request.queryParameters["query"]?.trim()
            val tags = if (q.isNullOrBlank()) {
                tagSchema.getAllTags() // List<TagDto> — TagDto is @Serializable
            } else {
                // For small datasets this is OK; for production use a DB ILIKE query instead
                tagSchema.getAllTags().filter { it.name.contains(q, ignoreCase = true) }
            }
            call.respond(HttpStatusCode.OK, tags)
        } catch (e: Exception) {
            call.application.log.error("Failed to fetch tags", e)
            call.respond(HttpStatusCode.InternalServerError, "Failed to fetch tags")
        }
    }

    // Authenticated: create tag
    authenticate("auth-jwt") {
        post("/tags") {
            val create = try {
                call.receive<TagCreateRequestDto>()
            } catch (e: Exception) {
                return@post call.respond(HttpStatusCode.BadRequest, "Invalid body")
            }

            val normalized = create.name.trim().trimStart('#')
            if (normalized.isBlank() || normalized.length > 50) {
                return@post call.respond(HttpStatusCode.BadRequest, "Invalid tag")
            }
            val allowed = Regex("^[A-Za-z0-9_\\- ]+\$")
            if (!allowed.matches(normalized)) {
                return@post call.respond(HttpStatusCode.BadRequest, "Invalid characters in tag")
            }

            try {
                val tagId = tagSchema.insertTag(normalized)
                val tagDto = TagDto(id = tagId, name = normalized)
                call.respond(HttpStatusCode.Created, tagDto)
            } catch (e: Exception) {
                call.application.log.error("Failed to create tag", e)
                call.respond(HttpStatusCode.InternalServerError, "Failed to create tag")
            }
        }
    }
}
