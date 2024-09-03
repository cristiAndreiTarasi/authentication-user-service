package example.com.plugins

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import example.com.plugins.routes.authenticationRoutes
import example.com.plugins.routes.streamRoutes
import example.com.plugins.routes.userRoutes
import example.com.schemas.ExposedUser
import example.com.schemas.StreamSchema
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
import kotlinx.datetime.*
import kotlinx.serialization.Serializable
import org.bson.types.ObjectId
import java.sql.Connection
import java.sql.SQLException
import java.time.LocalDate
import java.time.format.DateTimeFormatter

fun Application.configureRouting(
    userSchema: UserSchema,
    tokenSchema: TokenSchema,
    streamSchema: StreamSchema,
    hashingService: HashingService,
    tokenService: TokenService,
    postgresConnection: Connection,
) {
    routing {
        authenticationRoutes(userSchema, tokenSchema, hashingService, tokenService)
        userRoutes(userSchema, tokenSchema, postgresConnection)
        streamRoutes(streamSchema, userSchema)
    }
}
