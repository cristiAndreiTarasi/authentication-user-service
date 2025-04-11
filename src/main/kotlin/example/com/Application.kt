package example.com

import example.com.config.Constants
import example.com.plugins.*
import example.com.schemas.CategorySchema
import example.com.schemas.StreamSchema
import example.com.schemas.TagSchema
import example.com.schemas.TokenSchema
import example.com.schemas.UserSchema
import example.com.services.gridfs.GridFSService
import example.com.services.hashing.HashingService
import example.com.services.redis.VisitorCache
import example.com.services.role.RoleService
import example.com.services.token.TokenConfig
import example.com.services.token.TokenService
import io.ktor.server.application.*
import io.ktor.server.netty.*
import org.litote.kmongo.KMongo
import java.sql.Connection
import java.time.Duration

fun main(args: Array<String>): Unit = EngineMain.main(args)

fun Application.module() {
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

    configureSerialization()
    configureHTTP()
    configureSockets(tokenService, roleService)
    configureSecurity()
    configureRouting(
        userSchema,
        tokenSchema,
        streamSchema,
        hashingService,
        tokenService,
        postgresConnection,
        gridFsService
    )
}
