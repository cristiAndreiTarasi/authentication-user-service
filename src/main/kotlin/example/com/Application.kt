package example.com

import example.com.config.Constants
import example.com.data.TokenSchema
import example.com.data.UserSchema
import example.com.security.hashing.HashingService
import example.com.plugins.*
import example.com.security.token.TokenConfig
import example.com.security.token.TokenService
import io.ktor.server.application.*
import io.ktor.server.netty.*
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import java.time.Duration

fun main(args: Array<String>): Unit = EngineMain.main(args)

fun Application.module() {
    val config = environment.config

    val flyway = Flyway.configure()
        .dataSource(
            config.propertyOrNull("flyway.url")?.getString(),
            config.propertyOrNull("flyway.user")?.getString(),
            config.propertyOrNull("flyway.password")?.getString()
        )
        .locations(config.propertyOrNull("flyway.locations")?.getString())
        .load()

    flyway.migrate()

    val postgres = Database.connect(
        url = config.property("postgres.url").getString(),
        driver = config.property("postgres.driver").getString(),
        user = config.property("postgres.user").getString(),
        password = config.property("postgres.password").getString()
    )
    val tokenConfig = TokenConfig(
        issuer = environment.config.property("jwt.issuer").getString(),
        audience = environment.config.property("jwt.audience").getString(),
        accessExpiresIn = Duration.ofDays(1), // one hour
        refreshExpiresIn = Duration.ofDays(7), // one week
        secret = Constants.JWT_SECRET
    )

    val hashingService = HashingService()
    val tokenService = TokenService(tokenConfig)
    val userSchema = UserSchema(postgres)
    val tokenSchema = TokenSchema(postgres)

    configureSerialization()
    configureHTTP()
    configureSecurity()
    configureRouting(userSchema, tokenSchema, hashingService, tokenService)
}
