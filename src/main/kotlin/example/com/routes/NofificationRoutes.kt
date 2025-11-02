package example.com.routes

import example.com.schemas.NotificationSchema
import example.com.services.token.ITokenService
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import io.ktor.server.auth.jwt.JWTPrincipal

fun Route.notificationRoutes(
    notificationSchema: NotificationSchema,
    authTokenService: ITokenService
) {
    authenticate("auth-jwt") {
        route("/notifications") {
            /** Fetch all notifications for a given user */
            get {
                val principal = call.principal<JWTPrincipal>() ?: return@get call.respond(HttpStatusCode.Unauthorized)

                // Use the same pattern as userRoutes
                val idStr = authTokenService.getClaim(principal, "userId") ?: authTokenService.getClaim(principal, "sub")
                val userId = idStr?.toIntOrNull() ?: return@get call.respond(HttpStatusCode.Unauthorized, "Invalid user ID")

                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0

                try {
                    val notifications = notificationSchema.fetchNotifications(userId, limit, offset)
                    call.respond(HttpStatusCode.OK, notifications)
                } catch (e: Exception) {
                    call.application.log.error("Failed to fetch notifications for user $userId", e)
                    call.respond(HttpStatusCode.InternalServerError, "Failed to fetch notifications")
                }
            }

            /** Mark a notification as read */
            put("/{id}/read") {
                val principal = call.principal<JWTPrincipal>() ?: return@put call.respond(HttpStatusCode.Unauthorized)

                val idStr = authTokenService.getClaim(principal, "userId") ?: authTokenService.getClaim(principal, "sub")
                val userId = idStr?.toIntOrNull() ?: return@put call.respond(HttpStatusCode.Unauthorized, "Invalid user ID")

                val notifId = call.parameters["id"]?.toIntOrNull()
                    ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid notification id")

                val success = notificationSchema.markAsRead(userId, notifId)
                if (success) {
                    call.respond(HttpStatusCode.OK, mapOf("success" to true))
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Notification not found"))
                }
            }

            /** Delete a notification */
            delete("/{id}") {
                val principal = call.principal<JWTPrincipal>() ?: return@delete call.respond(HttpStatusCode.Unauthorized)

                val idStr = authTokenService.getClaim(principal, "userId") ?: authTokenService.getClaim(principal, "sub")
                val userId = idStr?.toIntOrNull() ?: return@delete call.respond(HttpStatusCode.Unauthorized, "Invalid user ID")

                val notifId = call.parameters["id"]?.toIntOrNull()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, "Invalid notification id")

                val success = notificationSchema.deleteNotification(userId, notifId)
                if (success) {
                    call.respond(HttpStatusCode.OK, Unit)
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Notification not found"))
                }
            }

            // Mark all notifications as read
            put("/read-all") {
                val principal = call.principal<JWTPrincipal>() ?: return@put call.respond(HttpStatusCode.Unauthorized)

                val idStr = authTokenService.getClaim(principal, "userId") ?: authTokenService.getClaim(principal, "sub")
                val userId = idStr?.toIntOrNull() ?: return@put call.respond(HttpStatusCode.Unauthorized, "Invalid user ID")

                val success = notificationSchema.markAllAsRead(userId)
                if (success) {
                    call.respond(HttpStatusCode.OK, mapOf("success" to true))
                } else {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to mark all as read"))
                }
            }

            // Get unread count for badge
            get("/unread-count") {
                val principal = call.principal<JWTPrincipal>() ?: return@get call.respond(HttpStatusCode.Unauthorized)

                val idStr = authTokenService.getClaim(principal, "userId") ?: authTokenService.getClaim(principal, "sub")
                val userId = idStr?.toIntOrNull() ?: return@get call.respond(HttpStatusCode.Unauthorized, "Invalid user ID")

                val count = notificationSchema.getUnreadCount(userId)
                call.respond(HttpStatusCode.OK, mapOf("count" to count))
            }
        }
    }
}
