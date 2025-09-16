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
    }

    val postgresConnection: Connection = connectToPostgres(embedded = false)
//    val mongoDatabase: MongoDatabase = connectToMongoDB()
    val mongoClient = KMongo.createClient(Constants.CONNECTION_STRING)
    val mongoDatabase = mongoClient.getDatabase(Constants.MONGODB_CLUSTER)

    val tokenConfig = TokenConfig(
        issuer = environment.config.property("jwt.issuer").getString(),
        audience = environment.config.property("jwt.audience").getString(),
        accessExpiresIn = Duration.ofHours(1), // one hour
        refreshExpiresIn = Duration.ofDays(7), // one week
        secret = Constants.JWT_SECRET
    )

    val hashingService = HashingService()
    val tokenService = TokenService(tokenConfig)
    val roleService = RoleService()
    val gridFsService = GridFSService(mongoDatabase, postgresConnection)
    val userSchema = UserSchema(postgresConnection, gridFsService)
    val tokenSchema = TokenSchema(postgresConnection)
    val categorySchema = CategorySchema(postgresConnection)
    val tagSchema = TagSchema(postgresConnection)
    val streamSchema = StreamSchema(postgresConnection, categorySchema, tagSchema, gridFsService)
    val eventSchema = EventSchema(postgresConnection, categorySchema, tagSchema)

    val redisManager = RedisManager("redis://${environment.config.property("db.redis.host").getString()}:${environment.config.property("db.redis.port").getString()}")

    // Start Redis consumer in background
    launch {
        redisManager.consumeEvents("live-group")
    }

    configureSerialization()
    configureHTTP()
    configureSockets(
        redisManager,
        userSchema,
        tokenService,
    )
    configureSecurity()
    configureRouting(
        userSchema,
        tokenSchema,
        streamSchema,
        eventSchema,
        tagSchema,
        categorySchema,
        hashingService,
        tokenService,
        postgresConnection,
        gridFsService
    )
}
