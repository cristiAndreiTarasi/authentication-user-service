package example.com

import com.zaxxer.hikari.HikariDataSource
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
import example.com.services.gridfs.GridFSService
import example.com.services.hashing.HashingService
import example.com.services.redis.RedisService
import example.com.services.role.RoleService
import example.com.services.token.TokenConfig
import example.com.services.token.TokenService
import example.com.services.ws_session.CrossInstanceBroadcaster
import example.com.services.ws_session.DistributedPermissionManager
import example.com.services.ws_session.DistributedSessionManager
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
import java.util.UUID
import javax.sql.DataSource

fun main(args: Array<String>): Unit = EngineMain.main(args)

fun Application.module() {
    val httpClient = createHttpClient(appJson)

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

    val host = environment.config.property("db.redis.host").getString()
    val port = environment.config.property("db.redis.port").getString()
    val redisService = RedisService("redis://$host:$port")

    // Generate unique instance ID for distributed tracking
    val instanceId = System.getenv("HOSTNAME") ?: "instance-${UUID.randomUUID().toString().take(8)}"

    // Initialize distributed managers at application level
    val distributedSessionManager = DistributedSessionManager(redisService, instanceId)
    val distributedPermissionManager = DistributedPermissionManager(redisService)
    val crossInstanceBroadcaster = CrossInstanceBroadcaster(redisService, instanceId)

    //moderation
    val moderationPublishSecret = environment.config.property("jwt.moderation.publishSecret").getString()

    val authTokenService = TokenService(authTokenConfig)
    val publishTokenService = TokenService(publishTokenConfig)

    val hashingService = HashingService()
    val roleService = RoleService()
    val gridFsService = GridFSService(mongoDatabase, dataSource)
    val userSchema = UserSchema(dataSource, gridFsService)
    val tokenSchema = TokenSchema(dataSource)
    val categorySchema = CategorySchema(dataSource)
    val tagSchema = TagSchema(dataSource)
    val streamSchema = StreamSchema(dataSource, categorySchema, tagSchema, gridFsService)
    val eventSchema = EventSchema(dataSource, categorySchema, tagSchema)

    val notificationSchema = NotificationSchema(dataSource)

    // initialize all distributed services
    val serviceManager = ServiceManager(
        redisService = redisService,
        userSchema = userSchema,
        notificationSchema = notificationSchema,
        authTokenService = authTokenService
    )

    // Start all background services
    serviceManager.startAllServices()

    configureSecurity(authTokenConfig)
    configureSerialization(appJson)
    configureHTTP()
    configureSockets(
        redisService,
        userSchema,
        notificationSchema,
        authTokenService,
        serviceManager
    )
    configureRouting(
        userSchema, tokenSchema, streamSchema,
        eventSchema, tagSchema, categorySchema,
        hashingService, dataSource, gridFsService,
        httpClient, authTokenService, publishTokenService,
        redisService, moderationPublishSecret, notificationSchema,
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
                val isConnected = redisService.ping()
                val redisInfo = mapOf(
                    "connected" to isConnected,
                    "instanceId" to serviceManager.instanceId
                )
                call.respond(redisInfo)
            }
        }
    }

    // Graceful shutdown handling
    environment.monitor.subscribe(ApplicationStopping) {
        println("🛑 Application stopping - cleaning up resources...")

        serviceManager.stopAllServices()
        redisService.close()
        httpClient.close()

        // Close Hikari if we have it
        try {
            (dataSource as? HikariDataSource)?.close()
        } catch (_: Throwable) {}

        println("✅ Cleanup completed")
    }
}
