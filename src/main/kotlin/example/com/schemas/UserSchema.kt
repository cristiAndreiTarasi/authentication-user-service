package example.com.schemas

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement
import java.sql.Timestamp

@Serializable
data class ExposedUser(
    val id: Int? = null,
    val email: String,
    var password: String,
    val salt: String,
    val username: String? = null,
    val passwordResetToken: String? = null,
    val passwordResetTokenExpiry: LocalDateTime? = null,
    val bio: String? = null,
    val occupation: String? = null,
    val imageUrl: String? = null,
    val createdAt: LocalDateTime
)

class UserSchema(private val dbConnection: Connection) {
    companion object {
        private const val INSERT_USER = "INSERT INTO users (email, password, salt, username, created_at) VALUES (?, ?, ?, ?, ?)"
        private const val SELECT_USER_BY_EMAIL = "SELECT * FROM users WHERE email = ?"
        private const val SELECT_USER_BY_USERNAME = "SELECT * FROM users WHERE username = ?"
        private const val SELECT_USER_BY_ID = "SELECT * FROM users WHERE id = ?"
        private const val SELECT_USER_BY_TOKEN = "SELECT * FROM users WHERE password_reset_token = ?"
        private const val UPDATE_PASSWORD_RESET_TOKEN = "UPDATE users SET password_reset_token = ?, password_reset_token_expiry = ? WHERE id = ?"
        private const val UPDATE_USER_PASSWORD = "UPDATE users SET password = ? WHERE id = ?"
        private const val SELECT_ALL_USERS = "SELECT * FROM users"
        private const val DELETE_USER_BY_ID = "DELETE FROM users WHERE id = ?"
        private const val UPDATE_USER_BIO = "UPDATE users SET bio = ? WHERE id = ?"
        private const val UPDATE_USER_OCCUPATION = "UPDATE users SET occupation = ? WHERE id = ?"
        private const val UPDATE_USER_NAME = "UPDATE users SET username = ? WHERE id = ?"
        private const val UPDATE_USER_AVATAR = "UPDATE users SET image_url = ? WHERE id = ?"
    }

    init {
        dbConnection.createStatement()
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    suspend fun insertUser(user: ExposedUser): Int = dbQuery {
        val statement = dbConnection.prepareStatement(INSERT_USER, Statement.RETURN_GENERATED_KEYS)

        statement.setString(1, user.email)
        statement.setString(2, user.password)
        statement.setString(3, user.salt)
        statement.setString(4, user.username)
        statement.setTimestamp(5, Timestamp.valueOf(user.createdAt.toJavaLocalDateTime()))

        println("Executing SQL: $INSERT_USER with values (${user.email}, ${user.password}, ${user.salt}, ${user.username}, ${user.createdAt})")

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

    suspend fun findByEmail(email: String): ExposedUser? = dbQuery {
        val statement = dbConnection.prepareStatement(SELECT_USER_BY_EMAIL)
        statement.setString(1, email)
        val resultSet = statement.executeQuery()
        if (resultSet.next()) {
            return@dbQuery resultSet.toUser()
        } else {
            return@dbQuery null
        }
    }

    suspend fun findByUsername(username: String): ExposedUser? = dbQuery {
        val statement = dbConnection.prepareStatement(SELECT_USER_BY_USERNAME)
        statement.setString(1, username)
        val resultSet = statement.executeQuery()
        if (resultSet.next()) {
            return@dbQuery resultSet.toUser()
        } else {
            return@dbQuery null
        }
    }

    suspend fun findById(id: Int): ExposedUser? = dbQuery {
        val statement = dbConnection.prepareStatement(SELECT_USER_BY_ID)
        statement.setInt(1, id)
        val resultSet = statement.executeQuery()
        if (resultSet.next()) {
            return@dbQuery resultSet.toUser()
        } else {
            return@dbQuery null
        }
    }

    suspend fun findByToken(token: String): ExposedUser? = dbQuery {
        val statement = dbConnection.prepareStatement(SELECT_USER_BY_TOKEN)
        statement.setString(1, token)
        val resultSet = statement.executeQuery()
        if (resultSet.next()) {
            return@dbQuery resultSet.toUser()
        } else {
            return@dbQuery null
        }
    }

    suspend fun updatePasswordResetToken(userId: Int, token: String, expiresAt: LocalDateTime): Boolean = dbQuery {
        val statement = dbConnection.prepareStatement(UPDATE_PASSWORD_RESET_TOKEN)
        statement.setString(1, token)
        statement.setTimestamp(2, Timestamp.valueOf(expiresAt.toJavaLocalDateTime()))
        statement.setInt(3, userId)
        statement.executeUpdate() > 0
    }

    suspend fun updateUserPassword(userId: Int, newPassword: String): Boolean = dbQuery {
        val statement = dbConnection.prepareStatement(UPDATE_USER_PASSWORD)
        statement.setString(1, newPassword)
        statement.setInt(2, userId)
        statement.executeUpdate() > 0
    }

    suspend fun getAllUsers(): List<ExposedUser> = dbQuery {
        val statement = dbConnection.prepareStatement(SELECT_ALL_USERS)
        val resultSet = statement.executeQuery()
        resultSet.toUsers()
    }

    suspend fun getUserById(id: Int): ExposedUser? = dbQuery {
        val statement = dbConnection.prepareStatement(SELECT_USER_BY_ID)
        statement.setInt(1, id)
        val resultSet = statement.executeQuery()
        resultSet.toUsers().firstOrNull()
    }

    suspend fun updateBio(id: Int, bio: String) = dbQuery {
        val statement = dbConnection.prepareStatement(UPDATE_USER_BIO)
        statement.setString(1, bio)
        statement.setInt(2, id)
        statement.executeUpdate()
    }

    suspend fun updateOccupation(id: Int, occupation: String) = dbQuery {
        val statement = dbConnection.prepareStatement(UPDATE_USER_OCCUPATION)
        statement.setString(1, occupation)
        statement.setInt(2, id)
        statement.executeUpdate()
    }

    suspend fun updateUsername(id: Int, username: String) = dbQuery {
        val statement = dbConnection.prepareStatement(UPDATE_USER_NAME)
        statement.setString(1, username)
        statement.setInt(2, id)
        statement.executeUpdate()
    }

    suspend fun updateImage(id: Int, imageUrl: String) = dbQuery {
        val statement = dbConnection.prepareStatement(UPDATE_USER_AVATAR)
        statement.setString(1, imageUrl)
        statement.setInt(2, id)
        statement.executeUpdate()
    }

    suspend fun deleteUser(id: Int): Boolean = dbQuery {
        val statement = dbConnection.prepareStatement(DELETE_USER_BY_ID)
        statement.setInt(1, id)
        statement.executeUpdate() > 0
    }

    private fun ResultSet.toUser(): ExposedUser {
        return ExposedUser(
            id = getInt("id"),
            email = getString("email"),
            password = getString("password"),
            salt = getString("salt"),
            username = getString("username"),
            passwordResetToken = getString("password_reset_token"),
            passwordResetTokenExpiry = getTimestamp("password_reset_token_expiry")?.toLocalDateTime()
                ?.toKotlinLocalDateTime(),
            createdAt = getTimestamp("created_at").toLocalDateTime().toKotlinLocalDateTime()
        )
    }

    private fun ResultSet.toUsers(): List<ExposedUser> {
        val users = mutableListOf<ExposedUser>()
        while (next()) users.add(toUser())
        return users
    }
}