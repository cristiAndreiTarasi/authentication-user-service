package example.com.plugins

import braintreeRoutes
import example.com.routes.authenticationRoutes
import example.com.routes.categoryRoutes
import example.com.routes.eventRoutes
import example.com.routes.giftsRoutes
import example.com.routes.moderationRoutes
import example.com.routes.notificationRoutes
import example.com.routes.srsHttpHookRoutes
import example.com.routes.streamRoutes
import example.com.routes.tagRoutes
import example.com.routes.userRoutes
import example.com.schemas.CategorySchema
import example.com.schemas.EventSchema
import example.com.schemas.GiftsSchema
import example.com.schemas.NotificationSchema
import example.com.schemas.PaymentSchema
import example.com.schemas.StreamSchema
import example.com.schemas.TagSchema
import example.com.schemas.TokenSchema
import example.com.schemas.UserSchema
import example.com.services.ServiceManager
import example.com.services.braintree.BraintreeService
import example.com.services.gifts.GiftsService
import example.com.services.gridfs.GridFSService
import example.com.services.hashing.HashingService
import example.com.services.redis.ShardedRedisService
import example.com.services.token.ITokenService
import io.ktor.client.HttpClient
import io.ktor.server.application.Application
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
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
    shardedRedisService: ShardedRedisService,
    moderationPublishSecret: String,
    notificationSchema: NotificationSchema,
    serviceManager: ServiceManager,
    braintreeService: BraintreeService,
    paymentSchema: PaymentSchema,
    giftsSchema: GiftsSchema,
    giftsService: GiftsService
) {
    routing {
        route("/api") {
            authenticationRoutes(
                userSchema, tokenSchema,
                hashingService, authTokenService
            )

            userRoutes(
                userSchema, notificationSchema, authTokenService,
                dataSource, gridFSService, shardedRedisService
            )

            streamRoutes(
                streamSchema, gridFSService,
                publishTokenService, userSchema,
                serviceManager.distributedPermissionManager,
                shardedRedisService
            )

            eventRoutes(
                eventSchema, gridFSService,
                userSchema, streamSchema
            )

            tagRoutes(tagSchema)
            categoryRoutes(categorySchema)

            srsHttpHookRoutes(
                userSchema, publishTokenService, streamSchema,
                httpClient, shardedRedisService, moderationPublishSecret
            )

            moderationRoutes(
                shardedRedisService,
                streamSchema,
                moderationPublishSecret,
                serviceManager.distributedPermissionManager
            )

            notificationRoutes(
                notificationSchema,
                authTokenService
            )

            braintreeRoutes(
                braintreeService = braintreeService,
                paymentSchema = paymentSchema,
                authTokenService = authTokenService
            )

            giftsRoutes(
                giftsSchema = giftsSchema,
                giftsService = giftsService,
                authTokenService = authTokenService,
                streamSchema = streamSchema,
                shardedRedisService = shardedRedisService,
                userSchema = userSchema,
                serviceManager.distributedPermissionManager,
                serviceManager.crossInstanceBroadcaster
            )
        }
    }
}
