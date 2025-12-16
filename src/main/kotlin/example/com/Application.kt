package example.com

import com.braintreegateway.BraintreeGateway
import com.braintreegateway.Environment
import com.zaxxer.hikari.HikariDataSource
import example.com.config.AppJson
import example.com.config.Constants
import example.com.plugins.configureHTTP
import example.com.plugins.configureRouting
import example.com.plugins.configureSecurity
import example.com.plugins.configureSerialization
import example.com.plugins.configureSockets
import example.com.plugins.createPostgresDataSource
import example.com.schemas.CategorySchema
import example.com.schemas.EventSchema
import example.com.schemas.NotificationSchema
import example.com.schemas.StreamSchema
import example.com.schemas.TagSchema
import example.com.schemas.TokenSchema
import example.com.schemas.UserSchema
import example.com.services.ServiceManager
import example.com.services.braintree.BraintreeService
import example.com.services.braintree.createBraintreeGatewayFromConfig
import example.com.services.gridfs.GridFSService
import example.com.services.hashing.HashingService
import example.com.services.redis.RedisStreams
import example.com.services.redis.ShardedRedisService
import example.com.services.role.RoleService
import example.com.services.token.TokenConfig
import example.com.services.token.TokenService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.call
import io.ktor.server.netty.EngineMain
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.litote.kmongo.KMongo
import java.time.Duration
import javax.sql.DataSource

fun main(args: Array<String>): Unit = EngineMain.main(args)

fun Application.module() {
    val httpClient = createHttpClient(AppJson)

    // val mongoDatabase: MongoDatabase = connectToMongoDB()
    val mongoClient = KMongo.createClient(Constants.CONNECTION_STRING)
    val mongoDatabase = mongoClient.getDatabase(Constants.MONGODB_CLUSTER)

    val dataSource: DataSource = createPostgresDataSource(embedded = false)

    /*
    * token instantiation
    */
    val jwtIssuer = environment.config.property("jwt.issuer").getString()

    // auth config (env first)
    val authAudience = environment.config.property("jwt.auth.audience").getString()
    val authSecret = System.getenv("AUTH_JWT_SECRET")
        ?: environment.config.propertyOrNull("jwt.auth.authSecret")?.getString()
        ?: throw IllegalStateException("Missing AUTH_JWT_SECRET")

    val authTokenConfig = TokenConfig(
        issuer = jwtIssuer,
        audience = authAudience,
        accessExpiresIn = Duration.ofMinutes(environment.config.property("jwt.auth.accessExpiresMinutes").getString().toLong()),
        refreshExpiresIn = Duration.ofHours(environment.config.property("jwt.auth.refreshExpiresHours").getString().toLong()),
        secret = authSecret
    )

    // publish config
    val publishAudience = environment.config.property("jwt.publish.audience").getString()
    val publishSecret = System.getenv("PUBLISH_JWT_SECRET")
        ?: environment.config.propertyOrNull("jwt.publish.publishSecret")?.getString()
        ?: throw IllegalStateException("Missing PUBLISH_JWT_SECRET")

    val publishTokenConfig = TokenConfig(
        issuer = jwtIssuer,
        audience = publishAudience,
        accessExpiresIn = Duration.ofMinutes(environment.config.property("jwt.publish.accessExpiresMinutes").getString().toLong()),
        refreshExpiresIn = Duration.ofMinutes(0), // publish tokens shouldn't have refresh tokens
        secret = publishSecret
    )

    // Redis Sharding Configuration
    val redisChatUrl = System.getenv("REDIS_CHAT_URL") ?: "redis://localhost:6380"
    val redisModerationUrl = System.getenv("REDIS_MODERATION_URL") ?: "redis://localhost:6381"
    val redisAnalyticsUrl = System.getenv("REDIS_ANALYTICS_URL") ?: "redis://localhost:6382"
    val redisBillingUrl = System.getenv("REDIS_BILLING_URL") ?: "redis://localhost:6383"
    val redisSocialUrl = System.getenv("REDIS_SOCIAL_URL") ?: "redis://localhost:6384"
    val redisSessionsUrl = System.getenv("REDIS_SESSIONS_URL") ?: "redis://localhost:6385"

    val shardedRedisService = ShardedRedisService(
        chatRedisUrl = redisChatUrl,
        moderationRedisUrl = redisModerationUrl,
        analyticsRedisUrl = redisAnalyticsUrl,
        billingRedisUrl = redisBillingUrl,
        socialRedisUrl = redisSocialUrl,
        sessionsRedisUrl = redisSessionsUrl
    )

    //moderation
    val moderationPublishSecret = environment.config.property("jwt.moderation.publishSecret").getString()

    val authTokenService = TokenService(authTokenConfig)
    val publishTokenService = TokenService(publishTokenConfig)

    val hashingService = HashingService()
    val roleService = RoleService()
    val gridFSService = GridFSService(mongoDatabase, dataSource)
    val userSchema = UserSchema(dataSource, gridFSService)
    val tokenSchema = TokenSchema(dataSource)
    val categorySchema = CategorySchema(dataSource)
    val tagSchema = TagSchema(dataSource)
    val streamSchema = StreamSchema(dataSource, categorySchema, tagSchema, gridFSService)
    val eventSchema = EventSchema(dataSource, categorySchema, tagSchema)

    val notificationSchema = NotificationSchema(dataSource)

    // Initialize service manager BEFORE configuring sockets and routing
    val serviceManager = ServiceManager(
        shardedRedisService = shardedRedisService,
        userSchema = userSchema,
        notificationSchema = notificationSchema,
        authTokenService = authTokenService
    )

    // Start all background services
    serviceManager.startAllServices()

    // --- Braintree config (read env first, then application.conf) ---
    val braintreeGateway = BraintreeGateway(
        Environment.SANDBOX,
        System.getenv("BT_MERCHANT_ID") ?: environment.config.property("braintree.merchantId").getString(),
        System.getenv("BT_PUBLIC_KEY") ?: environment.config.property("braintree.publicKey").getString(),
        System.getenv("BT_PRIVATE_KEY") ?: environment.config.property("braintree.privateKey").getString()
    )
    val braintreeService = BraintreeService(braintreeGateway)

    configureSecurity(authTokenConfig)
    configureSerialization(AppJson)
    configureHTTP()
    configureSockets(
        shardedRedisService,
        userSchema,
        notificationSchema,
        authTokenService,
        serviceManager
    )
    configureRouting(
        userSchema              = userSchema,
        tokenSchema             = tokenSchema,
        streamSchema            = streamSchema,
        eventSchema             = eventSchema,
        tagSchema               = tagSchema,
        categorySchema          = categorySchema,
        hashingService          = hashingService,
        dataSource              = dataSource,
        gridFSService           = gridFSService,
        httpClient              = httpClient,
        authTokenService        = authTokenService,
        publishTokenService     = publishTokenService,
        shardedRedisService     = shardedRedisService,
        moderationPublishSecret = moderationPublishSecret,
        notificationSchema       = notificationSchema,
        serviceManager          = serviceManager,
        braintreeService        = braintreeService
    )

    // Health check endpoint for monitoring
    routing {
        route("/health") {
            get {
                val health = serviceManager.healthCheck()
                if (health.all { it.value }) {
                    call.respond(HttpStatusCode.OK, health)
                } else {
                    call.respond(HttpStatusCode.ServiceUnavailable, health)
                }
            }

            get("/instance") {
                call.respond(mapOf("instanceId" to serviceManager.instanceId))
            }

            get("/redis") {
                val isConnected = shardedRedisService.pingAll()
                val redisInfo = mapOf(
                    "connected" to isConnected,
                    "chat_stream" to RedisStreams.CHAT_STREAM,
                    "moderation_stream" to RedisStreams.MODERATION_STREAM,
                    "analytics_stream" to RedisStreams.ANALYTICS_STREAM,
                    "billing_stream" to RedisStreams.BILLING_STREAM,
                    "social_stream" to RedisStreams.SOCIAL_STREAM,
                    "instance_id" to serviceManager.instanceId
                )
                call.respond(redisInfo)
            }

            get("/shards") {
                val shardInfo = mapOf(
                    "chat" to redisChatUrl,
                    "moderation" to redisModerationUrl,
                    "analytics" to redisAnalyticsUrl,
                    "billing" to redisBillingUrl,
                    "social" to redisSocialUrl,
                    "sessions" to redisSessionsUrl,
                    "instance_id" to serviceManager.instanceId
                )
                call.respond(shardInfo)
            }
        }
    }

    // Graceful shutdown handling
    environment.monitor.subscribe(ApplicationStopping) {
        println("🛑 Application stopping - cleaning up resources...")

        serviceManager.stopAllServices()
        shardedRedisService.close()
        httpClient.close()

        // Close Hikari if we have it
        try {
            (dataSource as? HikariDataSource)?.close()
        } catch (_: Throwable) {}

        println("✅ Cleanup completed")
    }
}
