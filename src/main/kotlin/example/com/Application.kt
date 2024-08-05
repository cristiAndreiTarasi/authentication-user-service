package example.com

import example.com.config.Constants
import example.com.schemas.TokenSchema
import example.com.schemas.UserSchema
import example.com.services.hashing.HashingService
import example.com.plugins.*
import example.com.services.token.TokenConfig
import example.com.services.token.TokenService
import io.ktor.server.application.*
import io.ktor.server.netty.*
import java.sql.Connection
import java.time.Duration

fun main(args: Array<String>): Unit = EngineMain.main(args)

fun Application.module() {
    val dbConnection: Connection = connectToPostgres(embedded = true)

    val tokenConfig = TokenConfig(
        issuer = environment.config.property("jwt.issuer").getString(),
        audience = environment.config.property("jwt.audience").getString(),
        accessExpiresIn = Duration.ofDays(1), // one hour
        refreshExpiresIn = Duration.ofDays(7), // one week
        secret = Constants.JWT_SECRET
    )

    val hashingService = HashingService()
    val tokenService = TokenService(tokenConfig)
    val userSchema = UserSchema(dbConnection)
    val tokenSchema = TokenSchema(dbConnection)

    configureSerialization()
    configureHTTP()
    configureSecurity()
    configureRouting(userSchema, tokenSchema, hashingService, tokenService)
}
