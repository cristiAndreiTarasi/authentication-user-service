package example.com.plugins

import example.com.plugins.routes.authenticationRoutes
import example.com.plugins.routes.streamRoutes
import example.com.plugins.routes.userRoutes
import example.com.schemas.StreamSchema
import example.com.schemas.TokenSchema
import example.com.schemas.UserSchema
import example.com.services.hashing.HashingService
import example.com.services.token.TokenService
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import java.sql.Connection

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
