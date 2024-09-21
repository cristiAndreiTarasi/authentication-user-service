package example.com.plugins.routes

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import example.com.UserRole
import example.com.plugins.routes.dtos.AuthResponse
import example.com.plugins.routes.dtos.ForgotPasswordRequest
import example.com.plugins.routes.dtos.ForgotResponse
import example.com.plugins.routes.dtos.RefreshTokenRequest
import example.com.plugins.routes.dtos.RefreshTokenResponse
import example.com.plugins.routes.dtos.ResetPasswordRequest
import example.com.plugins.routes.dtos.ResetResponse
import example.com.plugins.routes.dtos.SigninRequestDto
import example.com.plugins.routes.dtos.SignoutRequest
import example.com.plugins.routes.dtos.SignoutResponse
import example.com.plugins.routes.dtos.SignupRequestDto
import example.com.plugins.routes.dtos.roles.authorize
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
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.format.DateTimeFormatter

fun Route.authenticationRoutes(
    userSchema: UserSchema,
    tokenSchema: TokenSchema,
    hashingService: HashingService,
    tokenService: TokenService
) {
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
        val timeZone = TimeZone.of(user.timezone)

        // Create a new user
        val newUser = ExposedUser(
            email = user.email,
            password = saltedHash.hash,
            salt = saltedHash.salt,
            birthDate = LocalDate.parse(user.birthDate, DateTimeFormatter.ofPattern("d MMM yyyy")),
            createdAt = Clock.System.now().toLocalDateTime(timeZone),
            username = username,
            role = UserRole.OWNER.roleName,
            imageUrl = null,
            timezoneId = user.timezone
        )

        // Save the user to the database
        try {
            userSchema.insertUser(newUser)
            call.respond(
                HttpStatusCode.Created,
                newUser
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
        val timeZone = TimeZone.of(user.timezoneId)
        val tokenClaim = TokenClaim("userId", user.id.toString())
        val roleClaim = TokenClaim("role", user.role)

        // Generate tokens
        val accessToken = tokenService.generateAccessToken(listOf(tokenClaim, roleClaim), timeZone.id)
        val refreshToken = tokenService.generateRefreshToken(timeZone.id)


        // Store the refresh token
        val tokenModel = Token(
            userId = user.id,
            token = refreshToken,
            expiresAt = Clock.System.now().plus(7, DateTimeUnit.DAY, timeZone).toLocalDateTime(timeZone),
            createdAt = Clock.System.now().toLocalDateTime(timeZone)
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
                message = "Successful authentication.",
                user
            )
        )
    }

    post("/token-refresh") {
        val request = call.receive<RefreshTokenRequest>()
        val storedToken = tokenSchema.findByToken(request.refreshToken)

        if (storedToken == null || !isTokenValid(storedToken.expiresAt)) {
            call.respond(HttpStatusCode.Unauthorized, "Invalid refresh token. Sign in.")
            return@post
        }

        // Fetch the user to get their timezone
        val user = userSchema.findById(storedToken.userId)
        if (user == null) {
            call.respond(HttpStatusCode.Unauthorized, "User not found.")
            return@post
        }

        val timeZone = TimeZone.of(user.timezoneId)

        val tokenClaim = TokenClaim("userId", user.id.toString())
        val roleClaim = TokenClaim("role", user.role)

        // Generate tokens
        val newAccessToken = tokenService.generateAccessToken(listOf(tokenClaim, roleClaim), timeZone.id)
        val newRefreshToken = tokenService.generateRefreshToken(timeZone.id)

        tokenSchema.deleteTokensForUser(storedToken.userId)

        val newTokenModel = Token(
            userId = storedToken.userId,
            token = newRefreshToken,
            expiresAt = Clock.System.now().plus(7, DateTimeUnit.DAY, TimeZone.currentSystemDefault()).toLocalDateTime(TimeZone.currentSystemDefault()),
            createdAt = Clock.System.now().toLocalDateTime(timeZone)
        )

        tokenSchema.create(newTokenModel)

        call.respond(
            HttpStatusCode.Created,
            RefreshTokenResponse(newAccessToken, newRefreshToken, "Token refreshed")
        )
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

    post("/signout") {
        val request = try {
            call.receive<SignoutRequest>()
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, SignoutResponse("Invalid request body"))
            return@post
        }

        // Check if user ID is valid
        val user = userSchema.findById(request.userId)
        if (user == null) {
            call.respond(HttpStatusCode.NotFound, SignoutResponse("User not found"))
            return@post
        }

        // Delete the user's refresh tokens
        try {
            val deleteResult = tokenSchema.deleteTokensForUser(request.userId)
            if (deleteResult) {
                call.respond(HttpStatusCode.OK, SignoutResponse("User signed out successfully"))
            } else {
                call.respond(HttpStatusCode.InternalServerError, SignoutResponse("Failed to sign out user"))
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, SignoutResponse("Failed to sign out user: ${e.message}"))
        }
    }
    authorize("owner") {
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

suspend fun generateUniqueUsername(userSchema: UserSchema): String {
    while (true) {
        val timestamp = Clock.System.now().toEpochMilliseconds()
        val randomPart = (100000000000..999999999999).random()
        val username = "user_${timestamp}_$randomPart"
        userSchema.findByUsername(username) ?: return username
    }
}

// This function loads the email configuration from a configuration file for the
// specified provider (e.g., "gmail" or "yahoo").
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