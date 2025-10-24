package example.com.plugins

import example.com.routes.authenticationRoutes
import example.com.routes.categoryRoutes
import example.com.routes.debugModerationRoutes
import example.com.routes.eventRoutes
import example.com.routes.moderationRoutes
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
import example.com.services.redis.RedisManager
import example.com.services.role.RoleService
import example.com.services.token.ITokenService
import example.com.services.token.TokenService
import io.ktor.client.HttpClient
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import okhttp3.OkHttpClient
import java.sql.Connection
import javax.sql.DataSource

fun Application.configureRouting(
    userSchema: UserSchema,
    tokenSchema: TokenSchema,
    streamSchema: StreamSchema,
    eventSchema: EventSchema,
    tagSchema: TagSchema,
    categorySchema: CategorySchema,
    hashingService: HashingService,
    dataSource: DataSource,
    gridFSService: GridFSService,
    httpClient: HttpClient,
    authTokenService: ITokenService,
    publishTokenService: ITokenService,
    redisManager: RedisManager,
    moderationPublishSecret: String
) {
    routing {
        authenticationRoutes(userSchema, tokenSchema, hashingService, authTokenService)
        userRoutes(userSchema, tokenSchema, dataSource, gridFSService)
        streamRoutes(streamSchema, gridFSService, publishTokenService, userSchema)
        eventRoutes(eventSchema, gridFSService, userSchema, streamSchema)
        tagRoutes(tagSchema)
        categoryRoutes(categorySchema)
        srsHttpHookRoutes(userSchema, publishTokenService, streamSchema, httpClient, redisManager, moderationPublishSecret)
        moderationRoutes(redisManager, streamSchema, moderationPublishSecret)
        debugModerationRoutes(moderationPublishSecret)
    }
}
