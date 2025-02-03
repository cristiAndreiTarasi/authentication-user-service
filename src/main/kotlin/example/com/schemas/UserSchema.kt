package example.com.schemas

import com.mongodb.client.MongoDatabase
import com.mongodb.client.gridfs.GridFSBuckets
import com.mongodb.client.gridfs.model.GridFSUploadOptions
import example.com.config.LocalDateSerializer
import example.com.config.ObjectIdSerializer
import example.com.services.gridfs.GridFSService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime
import kotlinx.serialization.Serializable
import org.bson.Document
import org.bson.types.ObjectId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.sql.*
import java.time.LocalDate

@Serializable
data class ExposedUser(
    val id: Int? = null,
    val email: String,
    var password: String,
    val salt: String,
    val username: String,
    val role: String,
    val passwordResetToken: String? = null,
    val passwordResetTokenExpiry: LocalDateTime? = null,
    val bio: String? = null,
    val occupation: String? = null,
    val imageUrl: String? = null,
    @Serializable(with = LocalDateSerializer::class) val birthDate: LocalDate? = null,
    @Serializable(with = ObjectIdSerializer::class) val imageId: ObjectId? = null,
    val createdAt: LocalDateTime,
    val timezoneId: String,
    val isLive: Boolean
)

@Serializable
data class TallyDto(
    val tally: Int,
    val userIds: List<Int>
)

class UserSchema(
    private val dbConnection: Connection,
    private val gridFSService: GridFSService
) {
    companion object {
        private const val INSERT_USER = """
            INSERT INTO users 
            (email, password, salt, username, role, bio, occupation, created_at, birth_date, timezone, is_live) 
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        private const val SELECT_USER_BY_EMAIL = "SELECT * FROM users WHERE email = ?"
        private const val SELECT_USER_BY_USERNAME = "SELECT * FROM users WHERE username = ?"
        private const val SELECT_USER_BY_ID = "SELECT * FROM users WHERE id = ?"
        private const val SELECT_USER_BY_TOKEN = "SELECT * FROM users WHERE password_reset_token = ?"
        private const val UPDATE_PASSWORD_RESET_TOKEN = """
            UPDATE users 
            SET password_reset_token = ?, password_reset_token_expiry = ? 
            WHERE id = ?
        """
        private const val UPDATE_USER_PASSWORD = "UPDATE users SET password = ? WHERE id = ?"
        private const val SELECT_ALL_USERS = "SELECT * FROM users"
        private const val DELETE_USER_BY_ID = "DELETE FROM users WHERE id = ?"
        private const val UPDATE_USER_BIO = "UPDATE users SET bio = ? WHERE id = ?"
        private const val UPDATE_USER_OCCUPATION = "UPDATE users SET occupation = ? WHERE id = ?"
        private const val UPDATE_USER_ISSTREAMING = "UPDATE users SET is_live = ? WHERE id = ?"
        private const val UPDATE_USER_NAME = "UPDATE users SET username = ? WHERE id = ?"
        private const val UPDATE_USER_AVATAR = "UPDATE users SET image_url = ? WHERE id = ?"
        private const val UPDATE_USER_IMAGE_ID = "UPDATE users SET image_id = ? WHERE id = ?"

        private const val SELECT_USER_LIKES = "SELECT liker_id FROM likes WHERE user_id = ?"
        private const val SELECT_USER_FOLLOWERS = "SELECT follower_id FROM followers WHERE followed_id = ?"
        private const val SELECT_USER_FOLLOWING = "SELECT followed_id FROM followers WHERE follower_id = ?"
    }

    suspend fun insertUser(user: ExposedUser): Int = dbQuery { connection ->
        val statement = connection.prepareStatement(INSERT_USER, Statement.RETURN_GENERATED_KEYS)

        statement.setString(1, user.email)
        statement.setString(2, user.password)
        statement.setString(3, user.salt)
        statement.setString(4, user.username)
        statement.setString(5, user.role)
        statement.setString(6, user.bio)
        statement.setString(7, user.occupation)
        statement.setTimestamp(8, Timestamp.valueOf(user.createdAt.toJavaLocalDateTime()))
        statement.setDate(9, user.birthDate.let { Date.valueOf(it) })
        statement.setString(10, user.timezoneId)
        statement.setBoolean(11, user.isLive)

        statement.executeUpdate()
        val generatedKeys = statement.generatedKeys
        if (generatedKeys.next()) {
            val userId = generatedKeys.getInt(1)
            println("User inserted with ID: $userId")
            return@dbQuery userId
        } else {
            throw Exception("Unable to retrieve the id of the newly inserted user")
        }
    }

    suspend fun findByEmail(email: String): ExposedUser? = dbQuery { connection ->
        val statement = connection.prepareStatement(SELECT_USER_BY_EMAIL)
        statement.setString(1, email)
        val resultSet = statement.executeQuery()

        if (resultSet.next()) {
            return@dbQuery resultSet.toUser()
        } else {
            return@dbQuery null
        }
    }

    suspend fun findByUsername(username: String): ExposedUser? = dbQuery { connection ->
        val statement = connection.prepareStatement(SELECT_USER_BY_USERNAME)
        statement.setString(1, username)
        val resultSet = statement.executeQuery()
        if (resultSet.next()) {
            return@dbQuery resultSet.toUser()
        } else {
            return@dbQuery null
        }
    }

    suspend fun findById(id: Int): ExposedUser? = dbQuery { connection ->
        val statement = connection.prepareStatement(SELECT_USER_BY_ID)
        statement.setInt(1, id)
        val resultSet = statement.executeQuery()
        if (resultSet.next()) {
            return@dbQuery resultSet.toUser()
        } else {
            return@dbQuery null
        }
    }

    suspend fun findByToken(token: String): ExposedUser? = dbQuery { connection ->
        val statement = connection.prepareStatement(SELECT_USER_BY_TOKEN)
        statement.setString(1, token)
        val resultSet = statement.executeQuery()
        if (resultSet.next()) {
            return@dbQuery resultSet.toUser()
        } else {
            return@dbQuery null
        }
    }

    suspend fun updatePasswordResetToken(userId: Int, token: String, expiresAt: LocalDateTime): Boolean = dbQuery { connection ->
        val statement = connection.prepareStatement(UPDATE_PASSWORD_RESET_TOKEN)
        statement.setString(1, token)
        statement.setTimestamp(2, Timestamp.valueOf(expiresAt.toJavaLocalDateTime()))
        statement.setInt(3, userId)
        statement.executeUpdate() > 0
    }

    suspend fun getAllUsers(): List<ExposedUser> = dbQuery { connection ->
        val statement = connection.prepareStatement(SELECT_ALL_USERS)
        val resultSet = statement.executeQuery()
        resultSet.toUsers()
    }

    suspend fun getUserById(id: Int): ExposedUser? = dbQuery { connection ->
        val statement = connection.prepareStatement(SELECT_USER_BY_ID)
        statement.setInt(1, id)
        val resultSet = statement.executeQuery()
        resultSet.toUsers().firstOrNull()
    }

    suspend fun updateUserPassword(userId: Int, newPassword: String): Boolean = dbQuery { connection ->
        val statement = connection.prepareStatement(UPDATE_USER_PASSWORD)
        statement.setString(1, newPassword)
        statement.setInt(2, userId)
        statement.executeUpdate() > 0
    }

    suspend fun updateBio(id: Int, bio: String) = dbQuery { connection ->
        val statement = connection.prepareStatement(UPDATE_USER_BIO)
        statement.setString(1, bio)
        statement.setInt(2, id)
        statement.executeUpdate()
    }

    suspend fun updateOccupation(id: Int, occupation: String) = dbQuery { connection ->
        val statement = connection.prepareStatement(UPDATE_USER_OCCUPATION)
        statement.setString(1, occupation)
        statement.setInt(2, id)
        statement.executeUpdate()
    }

    suspend fun updateUsername(id: Int, username: String) = dbQuery { connection ->
        val statement = connection.prepareStatement(UPDATE_USER_NAME)
        statement.setString(1, username)
        statement.setInt(2, id)
        statement.executeUpdate()
    }

    suspend fun updateUserImageId(userId: Int, imageId: String): Boolean = dbQuery { connection ->
        val statement = connection.prepareStatement(UPDATE_USER_IMAGE_ID)
        statement.setString(1, imageId)
        statement.setInt(2, userId)
        statement.executeUpdate() > 0
    }

    suspend fun updateIsStreaming(id: Int, isLive: Boolean) = dbQuery { connection ->
        val statement = connection.prepareStatement(UPDATE_USER_ISSTREAMING)
        statement.setBoolean(1, isLive)
        statement.setInt(2, id)
        statement.executeUpdate()
    }

    suspend fun deleteUser(id: Int): Boolean = dbQuery { connection ->
        val statement = connection.prepareStatement(DELETE_USER_BY_ID)
        statement.setInt(1, id)
        val userDeleted = statement.executeUpdate() > 0

        if (userDeleted) {
            val imageIdStr = gridFSService.getImageIdByUserId(id)
            val imageId = imageIdStr?.let { ObjectId(it) }
            if (imageId != null) {
                gridFSService.deleteImage(imageId)
            }
        }

        userDeleted
    }

    suspend fun getUserLikes(userId: Int): TallyDto = dbQuery { connection ->
        val statement = connection.prepareStatement(SELECT_USER_LIKES)
        statement.setInt(1, userId)
        val resultSet = statement.executeQuery()

        val likerIds = mutableListOf<Int>()
        while (resultSet.next()) {
            likerIds.add(resultSet.getInt("liker_id"))
        }

        TallyDto(tally = likerIds.size, userIds = likerIds)
    }

    suspend fun getUserFollowers(userId: Int): TallyDto = dbQuery { connection ->
        val statement = connection.prepareStatement(SELECT_USER_FOLLOWERS)
        statement.setInt(1, userId)
        val resultSet = statement.executeQuery()

        val followerIds = mutableListOf<Int>()
        while (resultSet.next()) {
            followerIds.add(resultSet.getInt("follower_id"))
        }

        TallyDto(tally = followerIds.size, userIds = followerIds)
    }

    suspend fun getUserFollowing(userId: Int): TallyDto = dbQuery { connection ->
        val statement = connection.prepareStatement(SELECT_USER_FOLLOWING)
        statement.setInt(1, userId)
        val resultSet = statement.executeQuery()

        val followingIds = mutableListOf<Int>()
        while (resultSet.next()) {
            followingIds.add(resultSet.getInt("followed_id"))
        }

        TallyDto(tally = followingIds.size, userIds = followingIds)
    }

    private fun ResultSet.toUser(): ExposedUser {
        return ExposedUser(
            id = getInt("id"),
            email = getString("email"),
            password = getString("password"),
            salt = getString("salt"),
            username = getString("username"),
            role = getString("role"),
            bio = getString("bio"),
            occupation = getString("occupation"),
            passwordResetToken = getString("password_reset_token"),
            passwordResetTokenExpiry = getTimestamp("password_reset_token_expiry")?.toLocalDateTime()
                ?.toKotlinLocalDateTime(),
            imageId = getString("image_id")?.let { ObjectId(it) },
            birthDate = getDate("birth_date").toLocalDate(),
            createdAt = getTimestamp("created_at").toLocalDateTime().toKotlinLocalDateTime(),
            timezoneId = getString("timezone"),
            isLive = getBoolean("is_live")
        )
    }

    private fun ResultSet.toUsers(): List<ExposedUser> {
        val users = mutableListOf<ExposedUser>()
        while (next()) users.add(toUser())
        return users
    }

    private suspend fun <T> dbQuery(block: suspend (Connection) -> T): T = withContext(Dispatchers.IO) {
        try {
            block(dbConnection)
        } catch (e: SQLException) {
            throw RuntimeException("Database query failed: ${e.message}", e)
        }
    }
}