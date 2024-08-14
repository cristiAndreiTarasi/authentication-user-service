package example.com.plugins

import com.mongodb.client.gridfs.GridFSBucket
import com.mongodb.client.gridfs.model.GridFSUploadOptions
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import example.com.schemas.ExposedUser
import example.com.schemas.Token
import example.com.schemas.TokenSchema
import example.com.schemas.UserSchema
import example.com.services.email.EmailConfig
import example.com.services.email.EmailService
import example.com.services.hashing.HashingService
import example.com.services.hashing.SaltedHash
import example.com.services.token.TokenClaim
import example.com.services.token.TokenService
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.*
import kotlinx.serialization.Serializable
import org.bson.types.ObjectId
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.sql.Connection
import java.sql.SQLException

@Serializable
data class SignupRequestDto(val email: String, val password: String)

@Serializable
data class SigninRequestDto(val email: String, val password: String)

@Serializable
data class ResetPasswordRequest(val token: String, val newPassword: String)

@Serializable
data class RefreshTokenRequest(val refreshToken: String)

@Serializable
data class ForgotPasswordRequest(val email: String)

@Serializable
data class RefreshTokenResponse(val accessToken: String, val refreshToken: String, val message: String)

@Serializable
data class ForgotResponse(val message: String)

@Serializable
data class ResetResponse(val message: String)

@Serializable
data class SignoutRequest(val userId: Int)

@Serializable
data class AuthResponseDto(val accessToken: String, val refreshToken: String)

@Serializable
data class AuthResponse(
    val newAccessToken: String? = null,
    val newRefreshToken: String? = null,
    val message: String? = null
)

@Serializable
data class UploadImageResponse(val message: String, val id: String)

@Serializable
data class FetchImageResponse(
    val imageData: ByteArray
)

fun Application.configureRouting(
    userSchema: UserSchema,
    tokenSchema: TokenSchema,
    hashingService: HashingService,
    tokenService: TokenService,
    postgresConnection: Connection,
) {
    suspend fun generateUniqueUsername(userSchema: UserSchema): String {
        while (true) {
            val timestamp = Clock.System.now().toEpochMilliseconds()
            val randomPart = (100000000000..999999999999).random()
            val username = "user_${timestamp}_$randomPart"
            userSchema.findByUsername(username) ?: return username
        }
    }

    fun isTokenValid(expiresAt: LocalDateTime): Boolean {
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        return now < expiresAt
    }

    // This function generates a random string of alphanumeric characters of the given length.
    fun generateRandomString(length: Int): String {
        val allowedChars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        return (1..length)
            .map { allowedChars.random() }
            .joinToString("")
    }

    routing {
        post("/signup") {
            val user = try {
                call.receive<SignupRequestDto>()
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    AuthResponse(message = "Invalid request format")
                )
                return@post
            }

            // Validate email and password
            if (user.email.isBlank() || user.password.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    AuthResponse(message = "Email and password must not be blank")
                )
                return@post
            }

            // Check if email already exists
            val existingUser = userSchema.findByEmail(user.email)

            if (existingUser != null) {
                call.respond(
                    HttpStatusCode.Conflict,
                    AuthResponse(message = "Email already exists")
                )
                return@post
            }

            // Hash the password
            val saltedHash = hashingService.generateSaltedHash(user.password)

            // Generate a unique username
            val username = generateUniqueUsername(userSchema)

            // Create a new user
            val newUser = ExposedUser(
                email = user.email,
                password = saltedHash.hash,
                salt = saltedHash.salt,
                username = username,
                createdAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            )

            // Save the user to the database
            try {
                userSchema.insertUser(newUser)
                call.respond(
                    HttpStatusCode.Created,
                    AuthResponse(
                        message = "User created successfully"
                    )
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    AuthResponse(message = "Failed to create user: ${e.message}")
                )
            }
        }

        post("/signin") {
            val request = call.receive<SigninRequestDto>()

            // Try to find user by email
            val user = userSchema.findByEmail(request.email)
            // If user not found, return Not Found response
            if (user == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    AuthResponse(message = "There is no user related to this email address. Please try again or signup.")
                )
                return@post
            }

            // Verify password using hashing service
            val isValidPassword = hashingService.verify(
                value = request.password,
                saltedHash = SaltedHash(
                    hash = user.password ?: "",
                    salt = user.salt ?: ""
                )
            )
            // If password is invalid, return Unauthorized response
            if (!isValidPassword) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    AuthResponse(message = "Incorrect email or password.")
                )
                return@post
            }

            val userId = user.id ?: run {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    AuthResponse(message = "User ID is null.")
                )
                return@post
            }

            val existingRefreshToken = tokenSchema.findByUserId(userId)

            // Generate tokens
            val accessToken = tokenService.generateAccessToken(TokenClaim("userId", user.id.toString()))
            val refreshToken = tokenService.generateRefreshToken()

            // Store the refresh token
            val tokenModel = Token(
                userId = user.id,
                token = refreshToken,
                expiresAt = Clock.System.now().plus(7, DateTimeUnit.DAY, TimeZone.currentSystemDefault()).toLocalDateTime(TimeZone.currentSystemDefault()),
                createdAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            )

            // Check if existingRefreshToken is null
            if (existingRefreshToken == null) tokenSchema.create(tokenModel)
            else tokenSchema.update(tokenModel)

            // Respond with tokens
            call.respond(
                HttpStatusCode.OK,
                AuthResponse(
                    accessToken,
                    refreshToken,
                    message = "Successful authentication."
                )
            )
        }

        authenticate("auth-jwt") {
            // This function loads the email configuration from a configuration file for the specified provider (e.g., "gmail" or "yahoo").
            fun loadEmailConfig(provider: String): EmailConfig {
                val config: Config = ConfigFactory.load().getConfig("email.$provider")

                return EmailConfig(
                    provider = config.getString("provider"),
                    smtpHost = config.getString("smtpHost"),
                    smtpPort = config.getInt("smtpPort"),
                    smtpEmail = config.getString("smtpEmail"),
                    smtpPassword = config.getString("smtpPassword"),
                    fromAddress = config.getString("fromAddress"),
                )
            }

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

            post("/forgot-password") {
                val request = call.receive<ForgotPasswordRequest>()
                val user = userSchema.findByEmail(request.email)
                val token = generateRandomString(32)
                val expiryTime = Clock.System.now().plus(1, DateTimeUnit.HOUR, TimeZone.currentSystemDefault()).toLocalDateTime(TimeZone.currentSystemDefault())

                if (user == null) {
                    call.respond(HttpStatusCode.Conflict, "User does not exist.")
                    return@post
                }

                userSchema.updatePasswordResetToken(user.id!!, token, expiryTime)

                val resetLink = "http://localhost:8081/reset?token=$token"
                val emailBody = """
                    <div style="background-color: #F6F6F6; display: block; max-width: 960px;">
                        <div style="color: #ECECEC; background-color: #F6F6F6; padding: 20px; max-width: 960px; paddin: 100px;">
                            <h1 style="color: #302E3E; font-family: Calibri; font-size: 46px; text-align: center;">BitFest</h1>
                            <p style="max-width: 600px; padding: 0 100px 0 100px; font-family: Calibri; font-size: 20px; color: #302E3E">
                                To reset your BitFest password, please click this link:
                            </p>
                            <a href="$resetLink" style="max-width: 600px; padding: 0 100px 0 100px; font-family: Calibri; font-size: 20px;">
                                http://localhost:8080/reset?token=$token
                            </a>
                            <p style="color: #999999; max-width: 600px; padding: 0 100px 0 100px; font-family: Calibri; font-size: 20px; color: #302E3E"">
                                Thanks,
                                <br/>
                                Bitfest Team
                            </p>
                        </div>
                    </div>
                """.trimIndent()

                // Accommodate the email sending to gmail or yahoo mail
                val emailConfig = when {
                    request.email.endsWith("@gmail.com") -> loadEmailConfig("gmail")
                    request.email.endsWith("@yahoo.com") -> {
                        call.respond(HttpStatusCode.Forbidden, "Sorry, at the moment yahoo mail is not supported.")
                        loadEmailConfig("yahoo")
                    }
                    else -> throw IllegalArgumentException("Unsupported email provider")
                }
                val emailService = EmailService(emailConfig)

                try {
                    emailService.sendEmail(user.email, "BitFest Password Reset", emailBody)
                    call.respond(
                        HttpStatusCode.OK,
                        ForgotResponse(
                            message = "An email was sent to ${user.email} with instructions on how to reset your password."
                        ),
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ForgotResponse(
                            message = "Failed to send password reset email. $e"
                        )
                    )
                }
            }

            post("/reset-password") {
                val request = call.receive<ResetPasswordRequest>()

                // Verify if the token exists and has not expired and retrieve the user by the given token
                val user = userSchema.findByToken(request.token)

                if (user?.passwordResetTokenExpiry == null || !isTokenValid(user.passwordResetTokenExpiry)) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid or expired token.")
                    return@post
                }

                val newSaltedHash = hashingService.generateSaltedHash(request.newPassword)
                val updateResult = userSchema.updateUserPassword(user.id!!, newSaltedHash.hash)

                tokenSchema.deleteTokensForUser(user.id)

                if (updateResult) {
                    call.respond(
                        HttpStatusCode.OK,
                        ResetResponse(
                            message = "Password reset successfully."
                        )
                    )
                } else {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ResetResponse(
                            message = "Failed to reset the password."
                        )
                    )
                }
            }

            post("/token-refresh") {
                val request = call.receive<RefreshTokenRequest>()
                val storedToken = tokenSchema.findByToken(request.refreshToken)



                if (storedToken == null || !isTokenValid(storedToken.expiresAt)) {
                    call.respond(HttpStatusCode.Unauthorized, "Invalid refresh token. Sign in.")
                    return@post
                }

                val newAccessToken = tokenService.generateAccessToken(TokenClaim("userId", storedToken.userId.toString()))
                val newRefreshToken = tokenService.generateRefreshToken()

                val newTokenModel = Token(
                    userId = storedToken.userId,
                    token = newRefreshToken,
                    expiresAt = Clock.System.now().plus(7, DateTimeUnit.DAY, TimeZone.currentSystemDefault()).toLocalDateTime(TimeZone.currentSystemDefault()),
                    createdAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                )

                tokenSchema.update(newTokenModel)

                call.respond(
                    HttpStatusCode.Created,
                    RefreshTokenResponse(newAccessToken, newRefreshToken, "Token refreshed")
                )
            }

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
                        call.respond(HttpStatusCode.InternalServerError, "Failed to delete user tokens")
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

            put("/user/update/{id}/bio") {
                val id = call.parameters["id"]?.toIntOrNull()

                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid user ID")
                    return@put
                }

                val bio = call.receive<String>()
                userSchema.updateBio(id, bio)
                call.respond(HttpStatusCode.OK, "User bio updated")
            }

            put("/user/update/{id}/occupation") {
                val id = call.parameters["id"]?.toIntOrNull()

                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid user ID")
                    return@put
                }

                val occupation = call.receive<String>()
                userSchema.updateOccupation(id, occupation)
                call.respond(HttpStatusCode.OK, "User occupation updated")
            }

            put("/user/update/{id}/username") {
                val id = call.parameters["id"]?.toIntOrNull()

                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid user ID")
                    return@put
                }

                val username = call.receive<String>()
                userSchema.updateUsername(id, username)
                call.respond(HttpStatusCode.OK, "User username updated")
            }

            post("/upload/image/{userId}") {
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
                        val updateSuccess = userSchema.updateUserImageId(userId, imageId.toHexString())
                        if (updateSuccess) {
                            call.respond(
                                HttpStatusCode.Created,
                                UploadImageResponse("File uploaded successfully", imageId.toHexString()),
                            )
                        } else {
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                UploadImageResponse("Failed to update user profile with image ID", "")
                            )
                        }
                    } catch (e: Exception) {
                        call.application.environment.log.error("Error uploading image or updating user profile", e)
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            UploadImageResponse("Error uploading image or updating user profile", "")
                        )
                    }
                } ?: call.respond(
                    HttpStatusCode.BadRequest,
                    UploadImageResponse("File content is missing", "")
                )
            }

            get("/fetch/image/{userId}") {
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

            post("/signout") {
                val request = try {
                    call.receive<SignoutRequest>()
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid request body")
                    return@post
                }

                // Check if user ID is valid
                val user = userSchema.findById(request.userId)
                if (user == null) {
                    call.respond(HttpStatusCode.NotFound, "User not found")
                    return@post
                }

                // Delete the user's refresh tokens
                try {
                    val deleteResult = tokenSchema.deleteTokensForUser(request.userId)
                    if (deleteResult) {
                        call.respond(HttpStatusCode.OK, "User signed out successfully")
                    } else {
                        call.respond(HttpStatusCode.InternalServerError, "Failed to sign out user")
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, "Failed to sign out user: ${e.message}")
                }
            }

        }
    }
}
