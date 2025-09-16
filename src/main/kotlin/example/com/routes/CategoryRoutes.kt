package example.com.routes

import example.com.routes.dtos.CategoryDto
import example.com.routes.dtos.CreateCategoryRequestDto
import example.com.schemas.CategorySchema
import io.ktor.server.routing.Route
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*

fun Route.categoryRoutes(categorySchema: CategorySchema) {
    // Public: list categories (autocomplete / initial taxonomy)
    get("/categories") {
        try {
            val q = call.request.queryParameters["query"]?.trim()
            val cats = if (q.isNullOrBlank()) {
                categorySchema.getAllCategories()
            } else {
                // lightweight in-memory filter (replace with DB ILIKE if performance needed)
                categorySchema.getAllCategories().filter { it.name.contains(q, ignoreCase = true) }
            }
            call.respond(HttpStatusCode.OK, cats)
        } catch (e: Exception) {
            call.application.log.error("Failed to fetch categories", e)
            call.respond(HttpStatusCode.InternalServerError, "Failed to fetch categories")
        }
    }

    // Authenticated: create category (optionally admin-only)
    authenticate("auth-jwt") {
        post("/categories") {
            val principal = call.principal<JWTPrincipal>()
            val role = principal?.payload?.getClaim("role")?.asString()
            // optional admin check
            if (role != "owner" && role != "admin") {
                return@post call.respond(HttpStatusCode.Forbidden, "Not allowed")
            }

            val request = try {
                call.receive<CreateCategoryRequestDto>() // define DTO with 'name' and optional 'imageUrl'
            } catch (e: Exception) {
                return@post call.respond(HttpStatusCode.BadRequest, "Invalid body")
            }

            val normalized = request.name.trim()
            if (normalized.isBlank() || normalized.length > 100) {
                return@post call.respond(HttpStatusCode.BadRequest, "Invalid category name")
            }

            try {
                val id = categorySchema.insertCategory(normalized)
                val cat = CategoryDto(id = id, name = normalized, imageUrl = request.imageUrl)
                call.respond(HttpStatusCode.Created, cat)
            } catch (e: Exception) {
                call.application.log.error("Failed to create category", e)
                call.respond(HttpStatusCode.InternalServerError, "Failed to create category")
            }
        }
    }
}
