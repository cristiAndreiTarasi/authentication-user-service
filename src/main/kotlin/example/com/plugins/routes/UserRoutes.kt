package example.com.plugins.routes

import example.com.UserRole
import example.com.plugins.routes.dtos.ProfileFieldUpdateResponse
import example.com.plugins.routes.dtos.UpdateBioDto
import example.com.plugins.routes.dtos.UpdateOccupationDto
import example.com.plugins.routes.dtos.UpdateUsernameDto
import example.com.plugins.routes.dtos.UploadImageResponse
import example.com.plugins.routes.dtos.roles.authorize
import example.com.schemas.ExposedUser
import example.com.schemas.TokenSchema
import example.com.schemas.UserSchema
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable
import org.bson.types.ObjectId
import java.sql.Connection
import java.sql.SQLException

fun Route.userRoutes(
    userSchema: UserSchema,
    tokenSchema: TokenSchema,
    postgresConnection: Connection,
) {
    authorize {
        get("/users") {
            val users = userSchema.getAllUsers()
            if (users.isNotEmpty()) {
                call.respond(HttpStatusCode.OK, users)
            } else call.respond(HttpStatusCode.NotFound, "No users found")
        }

        get("/users/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()

            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, "Invalid user ID")
                return@get
            }

            val user = userSchema.getUserById(id)
            if (user != null) call.respond(HttpStatusCode.OK, user)
            else call.respond(HttpStatusCode.NotFound, "User not found")
        }

        authorize (UserRole.OWNER.roleName) {
            delete("/delete-user/{userId}") {
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

                val imageIdString = userSchema.getImageIdByUserId(userId)
                val imageId = imageIdString?.let { ObjectId(it) }

                try {
                    postgresConnection.autoCommit = false

                    val tokenDeleteResult = tokenSchema.deleteTokensForUser(userId)
                    if (!tokenDeleteResult) {
                        postgresConnection.rollback()
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            "Failed to delete user tokens"
                        )
                        return@delete
                    }

                    val deleteResult = userSchema.deleteUser(userId)
                    if (!deleteResult) {
                        postgresConnection.rollback()
                        call.respond(HttpStatusCode.InternalServerError, "Failed to delete user")
                        return@delete
                    }

                    imageId?.let { userSchema.deleteImage(it) }

                    postgresConnection.commit()
                    call.respond(HttpStatusCode.OK, "User deleted successfully")
                } catch (e: SQLException) {
                    postgresConnection.rollback()
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        "Failed to delete user and tokens: ${e.message}"
                    )
                } finally {
                    postgresConnection.autoCommit = true
                }
            }
        }

        authorize (
            UserRole.OWNER.roleName,
            UserRole.ADMIN.roleName
        ) {
            put("/users/update/{userId}/bio") {
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

            put("/users/update/{userId}/occupation") {
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

            put("/users/update/{userId}/username") {
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

            post("/users/update/{userId}/image") {
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

                fileContent?.let {
                    try {
                        val imageId = userSchema.uploadImage(userId, it)
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

        get("/users/fetch/{userId}/image") {
            val userId = call.parameters["userId"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, "User ID is missing")

            // Fetch the image ID associated with the user ID from the database
            val imageIdString = userSchema.getImageIdByUserId(userId)
                ?: return@get call.respond(HttpStatusCode.NotFound, "Image not found for user")

            val imageId = try {
                ObjectId(imageIdString)
            } catch (e: IllegalArgumentException) {
                return@get call.respond(HttpStatusCode.BadRequest, "Invalid Image ID")
            }

            val imageBytes = userSchema.fetchImage(imageId)
            if (imageBytes.isNotEmpty()) {
                call.respondBytes(imageBytes, ContentType.Image.JPEG)
            } else {
                call.respond(HttpStatusCode.NotFound, "Image not found")
            }
        }

        get("/users/{userId}/likes") {
            val userId = call.parameters["userId"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid user ID")

            val likes = userSchema.getUserLikes(userId)
            call.respond(HttpStatusCode.OK, likes)
        }

        get("/users/{userId}/followers") {
            val userId = call.parameters["userId"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid user ID")

            val followers = userSchema.getUserFollowers(userId)
            call.respond(HttpStatusCode.OK, followers)
        }

        get("/users/{userId}/following") {
            val userId = call.parameters["userId"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid user ID")

            val following = userSchema.getUserFollowing(userId)
            call.respond(HttpStatusCode.OK, following)
        }
    }
}