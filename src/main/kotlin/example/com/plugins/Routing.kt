package example.com.plugins

import example.com.routes.authenticationRoutes
import example.com.routes.categoryRoutes
import example.com.routes.eventRoutes
import example.com.routes.srsHttpHookRoutes
import example.com.routes.streamRoutes
import example.com.routes.tagRoutes
import example.com.routes.userRoutes
import example.com.schemas.CategorySchema
import example.com.schemas.EventSchema
import example.com.schemas.StreamSchema
import example.com.schemas.TagSchema
import example.com.schemas.TokenSchema
import example.com.schemas.UserSchema
import example.com.services.gridfs.GridFSService
import example.com.services.hashing.HashingService
import example.com.services.role.RoleService
import example.com.services.token.TokenService
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import java.sql.Connection

fun Application.configureRouting(
    userSchema: UserSchema,
    tokenSchema: TokenSchema,
    streamSchema: StreamSchema,
    eventSchema: EventSchema,
    tagSchema: TagSchema,
    categorySchema: CategorySchema,
    hashingService: HashingService,
    tokenService: TokenService,
    postgresConnection: Connection,
    gridFSService: GridFSService
) {
    routing {
        authenticationRoutes(userSchema, tokenSchema, hashingService, tokenService)
        userRoutes(userSchema, tokenSchema, postgresConnection, gridFSService)
        streamRoutes(streamSchema, gridFSService, userSchema)
        eventRoutes(eventSchema, gridFSService, userSchema, streamSchema)
        tagRoutes(tagSchema)
        categoryRoutes(categorySchema)
        srsHttpHookRoutes(userSchema, tokenService)
    }
}
