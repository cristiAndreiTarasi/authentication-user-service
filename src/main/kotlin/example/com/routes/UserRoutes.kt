// UserRoutes.kt
package example.com.routes

import example.com.SocialEventType
import example.com.routes.dtos.LiveUserDto
import example.com.routes.dtos.ProfileFieldUpdateResponse
import example.com.routes.dtos.UpdateBioDto
import example.com.routes.dtos.UpdateOccupationDto
import example.com.routes.dtos.UpdateUsernameDto
import example.com.routes.dtos.UploadImageResponse
import example.com.routes.dtos.UsernameResponse
import example.com.schemas.NotificationSchema
import example.com.schemas.UserSchema
import example.com.services.gridfs.GridFSService
import example.com.services.redis.RedisService
import example.com.services.token.ITokenService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import net.coobird.thumbnailator.Thumbnails
import org.bson.types.ObjectId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import javax.sql.DataSource

fun Route.userRoutes(
    userSchema: UserSchema,
    notificationSchema: NotificationSchema,
    authTokenService: ITokenService,
    dataSource: DataSource,
    gridFSService: GridFSService,
    redisService: RedisService
) {
    authenticate("auth-jwt") {
        route("/users") {
            get {
                val users = userSchema.getAllUsers()
                if (users.isNotEmpty()) {
                    call.respond(HttpStatusCode.OK, users)
                } else call.respond(HttpStatusCode.NotFound, "No users found")
            }

            get("/search") {
                val q = call.request.queryParameters["q"]?.trim() ?: ""
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0

                val principal = call.principal<JWTPrincipal>()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)

                // Prefer your tokenService.getClaim helper which returns string safely
                val idStr =
                    authTokenService.getClaim(principal, "userId") ?: authTokenService.getClaim(
                        principal,
                        "sub"
                    )
                val currentUserId = idStr?.toIntOrNull()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)

                val results = userSchema.searchUsers(q, limit, offset, currentUserId)
                call.respond(HttpStatusCode.OK, results)
            }

            get("/{id}") {
                val id = call.parameters["id"]?.toIntOrNull()

                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid user ID")
                    return@get
                }

                val user = userSchema.getUserById(id)
                if (user != null) call.respond(HttpStatusCode.OK, user)
                else call.respond(HttpStatusCode.NotFound, "User not found")
            }

            get("/live") {
                val liveUsers = userSchema.getLiveUsers()
                val dtos = liveUsers.map { u ->
                    LiveUserDto(
                        id = u.id!!,
                        username = u.username,
                        avatarPath = "/api/users/fetch/${u.id}/avatar"
                    )
                }
                call.respond(HttpStatusCode.OK, dtos)
            }

            get("/{userId}/username") {
                val userId = call.parameters["userId"]?.toIntOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid user ID")

                val user = userSchema.getUserById(userId)
                if (user != null) {
                    call.respond(HttpStatusCode.OK, UsernameResponse(username = user.username))
                } else {
                    call.respond(HttpStatusCode.NotFound, "User not found")
                }
            }

            get("/fetch/{userId}/avatar") {
                val userId = call.parameters["userId"]?.toIntOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, "User ID is missing")

                val imageIdString = gridFSService.getAvatarIdByUserId(userId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, "Image not found for user")

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

            get("/{userId}/likes") {
                val userId = call.parameters["userId"]?.toIntOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid user ID")

                val likes = userSchema.getUserLikes(userId)
                call.respond(HttpStatusCode.OK, likes)
            }

            get("/{userId}/followers") {
                val userId = call.parameters["userId"]?.toIntOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid user ID")

                val followers = userSchema.getUserFollowers(userId)
                call.respond(HttpStatusCode.OK, followers)
            }

            get("/{userId}/following") {
                val userId = call.parameters["userId"]?.toIntOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid user ID")

                val following = userSchema.getUserFollowing(userId)
                call.respond(HttpStatusCode.OK, following)
            }

            delete("/{userId}/delete") {
                val userId = call.parameters["userId"]?.toIntOrNull()
                if (userId == null) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid user ID")
                    return@delete
                }

                val userExists = userSchema.findById(userId)
                if (userExists == null) {
                    call.respond(HttpStatusCode.NotFound, "User not found")
                    return@delete
                }

                var avatarIdFromDb: String? = null

                // Borrow a single connection from the pool for the whole transaction
                dataSource.connection.use { conn ->
                    val origAuto = conn.autoCommit
                    try {
                        conn.autoCommit = false

                        // --- 1) Delete tokens in same transaction ---
                        // Prefer calling a transactional, non-suspending overload provided by TokenSchema:
                        // tokenSchema.deleteTokensForUserTransactional(conn, userId)
                        // If TokenSchema doesn't provide that, run the DELETE here using the connection:
                        val tokenDeleteOk: Boolean = try {
                            val deleteTokensSql = "DELETE FROM tokens WHERE user_id = ?"
                            conn.prepareStatement(deleteTokensSql).use { stmt ->
                                stmt.setInt(1, userId)
                                // executeUpdate returns rows affected
                                stmt.executeUpdate() >= 0 // true even if 0 rows
                            }
                        } catch (e: Exception) {
                            // if anything went wrong, rollback and rethrow
                            try {
                                conn.rollback()
                            } catch (_: Exception) {
                            }
                            throw e
                        }

                        // If you have a transactional TokenSchema method, you should use it instead so schema logic is centralized.

                        // --- 2) Delete user in same transaction and capture image id ---
                        // Prefer using the transactional overload on UserSchema if available:
                        // val avatarId = userSchema.deleteUserTransactional(conn, userId)
                        // We'll use userSchema.deleteUserTransactional(conn, userId) if present; otherwise run inline:
                        avatarIdFromDb = try {
                            // inline: get image_id then delete
                            var imageId: String? = null
                            conn.prepareStatement("SELECT image_id FROM users WHERE id = ?")
                                .use { sel ->
                                    sel.setInt(1, userId)
                                    sel.executeQuery().use { rs ->
                                        if (rs.next()) imageId = rs.getString("image_id")
                                    }
                                }

                            val deleted =
                                conn.prepareStatement("DELETE FROM users WHERE id = ?").use { del ->
                                    del.setInt(1, userId)
                                    del.executeUpdate() > 0
                                }

                            if (!deleted) {
                                // nothing deleted -> rollback + return failure
                                conn.rollback()
                                call.respond(
                                    HttpStatusCode.InternalServerError,
                                    "Failed to delete user"
                                )
                                return@delete
                            }

                            imageId
                        } catch (e: Exception) {
                            try {
                                conn.rollback()
                            } catch (_: Exception) {
                            }
                            throw e
                        }

                        // commit transaction
                        conn.commit()
                    } catch (e: Exception) {
                        try {
                            conn.rollback()
                        } catch (_: Exception) {
                        }
                        call.application.log.error("Failed to delete user and tokens", e)
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            "Failed to delete user and tokens: ${e.message}"
                        )
                        return@delete
                    } finally {
                        try {
                            conn.autoCommit = origAuto
                        } catch (_: Exception) {
                        }
                    }
                } // connection closed / returned to pool here

                // Remove GridFS image (outside DB transaction)
                avatarIdFromDb?.let { idStr ->
                    try {
                        gridFSService.deleteImage(ObjectId(idStr))
                    } catch (e: Exception) {
                        // non-fatal
                        call.application.environment.log.error(
                            "Failed to delete avatar from GridFS",
                            e
                        )
                    }
                }

                // success
                call.respond(HttpStatusCode.OK, "User deleted successfully")
            }

            post("/{targetId}/follow") {
                val principal = call.principal<JWTPrincipal>() ?: return@post call.respond(
                    HttpStatusCode.Unauthorized
                )
                val idStr =
                    authTokenService.getClaim(principal, "userId") ?: authTokenService.getClaim(
                        principal,
                        "sub"
                    )
                val currentUserId =
                    idStr?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.Unauthorized)

                val targetId =
                    call.parameters["targetId"]?.toIntOrNull() ?: return@post call.respond(
                        HttpStatusCode.BadRequest
                    )
                if (currentUserId == targetId) return@post call.respond(
                    HttpStatusCode.BadRequest,
                    "Cannot follow yourself"
                )
                val currentUser = userSchema.findById(currentUserId) ?: return@post call.respond(
                    HttpStatusCode.NotFound,
                    "User not found"
                )

                val ok = userSchema.followUser(currentUserId, targetId)
                if (ok) {
                    try {
                        notificationSchema.insertNotification(
                            userId = targetId,
                            actorId = currentUserId,
                            type = "follow",
                            text = "${currentUser.username} started following you",
                            meta = mapOf(
                                "followedAt" to Instant.now().toString(),
                                "actorAvatarUrl" to "/api/users/fetch/${currentUserId}/avatar"
                            )
                        )

                        redisService.addSocialEvent(
                            type = SocialEventType.FOLLOW,
                            actorId = currentUserId,
                            targetId = targetId,
                            actorUsername = currentUser.username
                        )
                    } catch (e: Exception) {
                        call.application.environment.log.error("Failed to publish social event", e)
                    }

                    call.respond(HttpStatusCode.Created)
                } else call.respond(HttpStatusCode.InternalServerError, "Failed to follow")
            }

            delete("/{targetId}/follow") {
                val principal = call.principal<JWTPrincipal>() ?: return@delete call.respond(
                    HttpStatusCode.Unauthorized
                )
                val idStr =
                    authTokenService.getClaim(principal, "userId") ?: authTokenService.getClaim(
                        principal,
                        "sub"
                    )
                val currentUserId =
                    idStr?.toIntOrNull() ?: return@delete call.respond(HttpStatusCode.Unauthorized)

                val targetId =
                    call.parameters["targetId"]?.toIntOrNull() ?: return@delete call.respond(
                        HttpStatusCode.BadRequest
                    )
                if (currentUserId == targetId) return@delete call.respond(
                    HttpStatusCode.BadRequest,
                    "Cannot unfollow yourself"
                )
                val currentUser = userSchema.findById(currentUserId) ?: return@delete call.respond(
                    HttpStatusCode.NotFound,
                    "User not found"
                )

                val ok = userSchema.unfollowUser(currentUserId, targetId)
                if (ok) {
                    try {
                        redisService.addSocialEvent(
                            type = SocialEventType.UNFOLLOW,
                            actorId = currentUserId,
                            targetId = targetId,
                            actorUsername = currentUser.username
                        )
                    } catch (e: Exception) {
                        call.application.environment.log.error("Failed to publish social event", e)
                    }
                    call.respond(HttpStatusCode.NoContent)
                } else call.respond(HttpStatusCode.InternalServerError, "Failed to unfollow")
            }

            put("/update/{userId}/bio") {
                val id = call.parameters["userId"]?.toIntOrNull()

                if (id == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ProfileFieldUpdateResponse("Invalid user ID")
                    )
                    return@put
                }

                val bio = call.receive<UpdateBioDto>()
                userSchema.updateBio(id, bio.bio)
                call.respond(HttpStatusCode.OK, ProfileFieldUpdateResponse("User bio updated"))
            }

            put("/update/{userId}/occupation") {
                val id = call.parameters["userId"]?.toIntOrNull()

                if (id == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ProfileFieldUpdateResponse("Invalid user ID")
                    )
                    return@put
                }

                val occupation = call.receive<UpdateOccupationDto>()
                userSchema.updateOccupation(id, occupation.occupation)
                call.respond(
                    HttpStatusCode.OK,
                    ProfileFieldUpdateResponse("User occupation updated")
                )
            }

            put("/update/{userId}/username") {
                val userId = call.parameters["userId"]?.toIntOrNull()

                if (userId == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ProfileFieldUpdateResponse("Invalid user ID")
                    )
                    return@put
                }

                val username = call.receive<UpdateUsernameDto>()
                userSchema.updateUsername(userId, username.username)
                call.respond(HttpStatusCode.OK, ProfileFieldUpdateResponse("User username updated"))
            }

            post("/update/{userId}/avatar") {
                val userId = call.parameters["userId"]?.toIntOrNull()
                    ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        UploadImageResponse("User ID is missing.", "")
                    )

                val multipartData = try {
                    call.receiveMultipart()
                } catch (e: Exception) {
                    call.application.environment.log.error("Error receiving multipart data", e)
                    return@post call.respond(
                        HttpStatusCode.InternalServerError,
                        UploadImageResponse("Error receiving multipart data", "")
                    )
                }
                var fileContent: ByteArray? = null

                try {
                    multipartData.forEachPart { part ->
                        when (part) {
                            is PartData.FileItem -> {
                                fileContent = part.streamProvider().readBytes()
                            }

                            else -> {
                                part.dispose()
                            }
                        }
                    }
                } catch (e: Exception) {
                    call.application.environment.log.error("Error processing multipart data", e)
                    return@post call.respond(
                        HttpStatusCode.InternalServerError,
                        UploadImageResponse("Error processing multipart data", "")
                    )
                }

                fileContent?.let { byteArray ->
                    try {
                        val imageId =
                            gridFSService.uploadImage(userId, byteArray, transformation = { data ->
                                val inputStream = ByteArrayInputStream(data)
                                val outputStream = ByteArrayOutputStream()

                                Thumbnails.of(inputStream)
                                    .forceSize(180, 180)
                                    .outputFormat("jpg")
                                    .outputQuality(0.8)
                                    .toOutputStream(outputStream)

                                outputStream.toByteArray()
                            })
                        val updateSuccess =
                            userSchema.updateUserImageId(userId, imageId.toHexString())

                        if (updateSuccess) {
                            call.respond(
                                HttpStatusCode.Created,
                                UploadImageResponse(
                                    "File uploaded successfully",
                                    imageId.toHexString()
                                ),
                            )
                        } else {
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                UploadImageResponse(
                                    "Failed to update user profile with image ID",
                                    ""
                                )
                            )
                        }
                    } catch (e: Exception) {
                        call.application.environment.log.error(
                            "Error uploading image or updating user profile",
                            e
                        )
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            UploadImageResponse(
                                "Error uploading image or updating user profile",
                                ""
                            )
                        )
                    }
                } ?: call.respond(
                    HttpStatusCode.BadRequest,
                    UploadImageResponse("File content is missing", "")
                )
            }
        }
    }
}
