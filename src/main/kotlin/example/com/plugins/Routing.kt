package example.com.plugins

import example.com.data.ExposedUser
import example.com.data.Token
import example.com.data.TokenSchema
import example.com.data.UserSchema
import example.com.security.hashing.HashingService
import example.com.security.hashing.SaltedHash
import example.com.security.token.TokenClaim
import example.com.security.token.TokenService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.*
import kotlinx.serialization.Serializable
import java.time.LocalDateTime

@Serializable
data class SignupRequest(
    val email: String,
    val password: String
)

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
            userSchema.findUserByUsername(username) ?: return username
        }
    }

    routing {
        post("/signup") {
            val user = call.receive<SignupRequest>()

            // Validate email and password
            if (user.email.isBlank() || user.password.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    "Email and password must not be blank"
                )
                return@post
            }

            // Check if email already exists
            val existingUser = userSchema.findUserByEmail(user.email)

            if (existingUser != null) {
                call.respond(HttpStatusCode.Conflict, "Email already exists")
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
                call.respond(HttpStatusCode.Created, "User created successfully")
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, "Failed to create user")
            }
        }

        post("signin") {
            val request = call.receive<SignupRequest>()

            // Try to find user by email
            val user = userSchema.findUserByEmail(request.email)
            // If user not found, return Not Found response
            if (user == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    "There is no user related to this email address. " +
                            "Please try again or signup."
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
                call.respond(HttpStatusCode.Unauthorized, "Incorrect email or password.")
                return@post
            }

            val userId = user.id ?: run {
                call.respond(HttpStatusCode.InternalServerError, "User ID is null.")
                return@post
            }

            val existingRefreshToken = tokenSchema.getRefreshTokenByUserId(userId)

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
            if (existingRefreshToken == null) tokenSchema.insertRefreshToken(tokenModel)
            else tokenSchema.updateRefreshToken(tokenModel)

            // Respond with tokens
            call.respond(
                mapOf(
                    "accessToken" to accessToken,
                    "refreshToken" to refreshToken
                )
            )
        }

        authenticate("auth-jwt") {
            post("forgot-password") {}

            post("reset-password") {}

            post("token-refresh") {}

            delete("delete-user/{userId}") {}
        }
    }
}
