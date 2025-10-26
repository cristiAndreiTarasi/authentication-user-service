package example.com.routes

import example.com.schemas.NotificationSchema
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import io.ktor.server.auth.jwt.JWTPrincipal

fun Route.notificationRoutes(notificationSchema: NotificationSchema) {
    authenticate("auth-jwt") {
        route("/users/{userId}/notifications") {

            /** Fetch all notifications for a given user */
            get {
                val principal = call.principal<JWTPrincipal>() ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val userIdParam = call.parameters["userId"]?.toIntOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid userId")

                // Ensure caller is the same user (or you could extend with admin checks)
                val subjectId = principal.payload.getClaim("id").asInt()
                if (subjectId != userIdParam) {
                    return@get call.respond(HttpStatusCode.Forbidden, "You can only access your own notifications")
                }

                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0

                val notifications = notificationSchema.fetchNotifications(userIdParam, limit, offset)
                call.respond(HttpStatusCode.OK, notifications)
            }

            /** Mark a notification as read */
            put("{id}/read") {
                val principal = call.principal<JWTPrincipal>() ?: return@put call.respond(HttpStatusCode.Unauthorized)
                val userIdParam = call.parameters["userId"]?.toIntOrNull()
                    ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid userId")
                val notifId = call.parameters["id"]?.toIntOrNull()
                    ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid notification id")

                val subjectId = principal.payload.getClaim("id").asInt()
                if (subjectId != userIdParam) {
                    return@put call.respond(HttpStatusCode.Forbidden, "You can only modify your own notifications")
                }

                val success = notificationSchema.markAsRead(userIdParam, notifId)
                if (success) {
                    call.respond(HttpStatusCode.OK, mapOf("success" to true))
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Notification not found"))
                }
            }
        }
    }
}
