package example.com.plugins

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
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.*
import kotlinx.serialization.Serializable

@Serializable
data class SignupRequest(val email: String, val password: String)

@Serializable
data class ResetPasswordRequest(val token: String, val newPassword: String)

@Serializable
data class RefreshTokenRequest(val refreshToken: String)

@Serializable
data class ForgotPasswordRequest(val email: String)

@Serializable
data class AuthResponse(val accessToken: String, val refreshToken: String, val message: String)

@Serializable
data class ForgotResponse(val message: String)

@Serializable
data class ResetResponse(val message: String)

@Serializable
data class SignoutRequest(val userId: Int)

@Serializable
data class ResponseMessage<T>(val result: T? = null, val error: String? = null)

fun Application.configureRouting(
    userSchema: UserSchema,
    tokenSchema: TokenSchema,
    hashingService: HashingService,
    tokenService: TokenService,
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
                call.receive<SignupRequest>()
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ResponseMessage<Unit>(error = "Invalid request format")
                )
                return@post
            }

            // Validate email and password
            if (user.email.isBlank() || user.password.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ResponseMessage<Unit>(
                        error = "Email and password must not be blank"
                    )
                )
                return@post
            }

            // Check if email already exists
            val existingUser = userSchema.findByEmail(user.email)

            if (existingUser != null) {
                call.respond(
                    HttpStatusCode.Conflict,
                    ResponseMessage<Unit>(
                        error = "Email already exists"
                    )
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
                    ResponseMessage(
                        "User created successfully"
                    )
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ResponseMessage<Unit>(
                        error = "Failed to create user: ${e.message}"
                    )
                )
            }
        }

        post("/signin") {
            val request = call.receive<SignupRequest>()

            // Try to find user by email
            val user = userSchema.findByEmail(request.email)
            // If user not found, return Not Found response
            if (user == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ResponseMessage<Unit>(
                    error = "There is no user related to this email address. Please try again or signup."
                    )
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
                    ResponseMessage<Unit>(
                        error = "Incorrect email or password."
                    )
                )
                return@post
            }

            val userId = user.id ?: run {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ResponseMessage<Unit>(
                        error ="User ID is null."
                    )
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
                ResponseMessage(
                    mapOf(
                        "accessToken" to accessToken,
                        "refreshToken" to refreshToken
                    )
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
                    call.respond(HttpStatusCode.OK, ResponseMessage(result = users))
                } else call.respond(HttpStatusCode.NotFound, ResponseMessage<List<ExposedUser>>(error = "No users found"))
            }

            get("/users/{id}") {
                val id = call.parameters["id"]?.toIntOrNull()

                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, ResponseMessage<ExposedUser>(error = "Invalid user ID"))
                    return@get
                }

                val user = userSchema.getUserById(id)
                if (user != null) call.respond(HttpStatusCode.OK, ResponseMessage(result = user))
                else call.respond(HttpStatusCode.NotFound, ResponseMessage<ExposedUser>(error = "User not found"))
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
                    AuthResponse(newAccessToken, newRefreshToken, "Token refreshed")
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

                val deleteResult = userSchema.deleteUser(userId)
                tokenSchema.deleteTokensForUser(userId)

                if (deleteResult) {
                    call.respond(HttpStatusCode.OK, "User deleted successfully")
                } else {
                    call.respond(HttpStatusCode.InternalServerError, "Failed to delete user")
                }
            }

            put("/user/update/{id}/bio") {
                val id = call.parameters["id"]?.toIntOrNull()

                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, ResponseMessage<String>(error = "Invalid user ID"))
                    return@put
                }

                val bio = call.receive<String>()
                userSchema.updateBio(id, bio)
                call.respond(HttpStatusCode.OK, ResponseMessage(result = "User bio updated"))
            }

            put("/user/update/{id}/occupation") {
                val id = call.parameters["id"]?.toIntOrNull()

                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, ResponseMessage<String>(error = "Invalid user ID"))
                    return@put
                }

                val occupation = call.receive<String>()
                userSchema.updateOccupation(id, occupation)
                call.respond(HttpStatusCode.OK, ResponseMessage(result = "User occupation updated"))
            }

            put("/user/update/{id}/username") {
                val id = call.parameters["id"]?.toIntOrNull()

                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, ResponseMessage<String>(error = "Invalid user ID"))
                    return@put
                }

                val username = call.receive<String>()
                userSchema.updateUsername(id, username)
                call.respond(HttpStatusCode.OK, ResponseMessage(result = "User username updated"))
            }

            put("/user/update/{id}/image") {
                val id = call.parameters["id"]?.toIntOrNull()

                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, ResponseMessage<String>(error = "Invalid user ID"))
                    return@put
                }

                val imageUrl = call.receive<String>()
                userSchema.updateImage(id, imageUrl)
                call.respond(HttpStatusCode.OK, ResponseMessage(result = "User image URL updated"))
            }

            post("/signout") {
                val request = try {
                    call.receive<SignoutRequest>()
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ResponseMessage<Unit>(error = "Invalid request body"))
                    return@post
                }

                // Check if user ID is valid
                val user = userSchema.findById(request.userId)
                if (user == null) {
                    call.respond(HttpStatusCode.NotFound, ResponseMessage<Unit>(error = "User not found"))
                    return@post
                }

                // Delete the user's refresh tokens
                try {
                    val deleteResult = tokenSchema.deleteTokensForUser(request.userId)
                    if (deleteResult) {
                        call.respond(HttpStatusCode.OK, ResponseMessage(result = "User signed out successfully"))
                    } else {
                        call.respond(HttpStatusCode.InternalServerError, ResponseMessage<Unit>(error = "Failed to sign out user"))
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, ResponseMessage<Unit>(error = "Failed to sign out user: ${e.message}"))
                }
            }

        }
    }
}
