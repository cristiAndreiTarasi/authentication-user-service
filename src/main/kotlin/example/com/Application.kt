package example.com

import example.com.config.Constants
import example.com.plugins.configureHTTP
import example.com.plugins.configureRouting
import example.com.plugins.configureSecurity
import example.com.plugins.configureSerialization
import example.com.plugins.configureSockets
import example.com.plugins.connectToPostgres
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
import example.com.services.token.TokenConfig
import example.com.services.token.TokenService
import example.com.services.ws_session.PermissionManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.logging.SIMPLE
import io.ktor.serialization.kotlinx.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.netty.EngineMain
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.litote.kmongo.KMongo
import java.sql.Connection
import java.time.Duration

fun main(args: Array<String>): Unit = EngineMain.main(args)

fun Application.module() {
    val httpClient = createHttpClient(appJson)

    val postgresConnection: Connection = connectToPostgres(embedded = false)
    // val mongoDatabase: MongoDatabase = connectToMongoDB()
    val mongoClient = KMongo.createClient(Constants.CONNECTION_STRING)
    val mongoDatabase = mongoClient.getDatabase(Constants.MONGODB_CLUSTER)

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

    val authTokenService = TokenService(authTokenConfig)
    val publishTokenService = TokenService(publishTokenConfig)

    val hashingService = HashingService()
    val roleService = RoleService()
    val gridFsService = GridFSService(mongoDatabase, postgresConnection)
    val userSchema = UserSchema(postgresConnection, gridFsService)
    val tokenSchema = TokenSchema(postgresConnection)
    val categorySchema = CategorySchema(postgresConnection)
    val tagSchema = TagSchema(postgresConnection)
    val streamSchema = StreamSchema(postgresConnection, categorySchema, tagSchema, gridFsService)
    val eventSchema = EventSchema(postgresConnection, categorySchema, tagSchema)

    val host = environment.config.property("db.redis.host").getString()
    val port = environment.config.property("db.redis.port").getString()

    val redisManager = RedisManager("redis://$host:$port")

    // Start Redis consumer in background
    launch {
        redisManager.consumeEvents("live-group")
    }

    configureSerialization(appJson)
    configureHTTP()
    configureSockets(
        redisManager,
        userSchema,
        authTokenService,
    )
    configureSecurity(authTokenConfig)
    configureRouting(
        userSchema, tokenSchema, streamSchema,
        eventSchema, tagSchema, categorySchema,
        hashingService, postgresConnection, gridFsService,
        httpClient, authTokenService, publishTokenService,
        redisManager
    )

    val cleanupJob = launch {
        while (true) {
            // Run every 6 hours
            delay(Duration.ofHours(6).toMillis())

            // Trigger cleanup
            PermissionManager.cleanupOldRooms()
        }
    }

    // Graceful shutdown handling
    environment.monitor.subscribe(ApplicationStopping) {
        cleanupJob.cancel() // Stop job when server stops
        redisManager.close()
        httpClient.close()
    }
}
