package example.com.schemas

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime
import kotlinx.serialization.Serializable
import java.sql.*

@Serializable
data class Token(
    val id: Int? = null,
    val userId: Int,
    val token: String,
    var expiresAt: LocalDateTime,
    var createdAt: LocalDateTime
)

class TokenSchema(private val dbConnection: Connection) {
    companion object {
        private const val INSERT_TOKEN = "INSERT INTO tokens (user_id, token, created_at, expires_at) VALUES (?, ?, ?, ?)"
        private const val SELECT_TOKEN = "SELECT * FROM tokens WHERE token = ?"
        private const val SELECT_TOKEN_BY_USER_ID = "SELECT * FROM tokens WHERE user_id = ?"
        private const val UPDATE_TOKEN = "UPDATE tokens SET token = ?, expires_at = ?, created_at = ? WHERE user_id = ?"
        private const val DELETE_TOKENS_FOR_USER = "DELETE FROM tokens WHERE user_id = ?"
    }

    init {
        dbConnection.createStatement()
    }

    private suspend fun <T> dbQuery(block: suspend (Connection) -> T): T = withContext(Dispatchers.IO) {
        try {
            block(dbConnection)
        } catch (e: SQLException) {
            throw RuntimeException("Database query failed: ${e.message}", e)
        }
    }

    suspend fun create(tokenModel: Token): Int = dbQuery { connection ->
        val statement = connection.prepareStatement(INSERT_TOKEN, Statement.RETURN_GENERATED_KEYS)
        statement.setInt(1, tokenModel.userId)
        statement.setString(2, tokenModel.token)
        statement.setTimestamp(3, Timestamp.valueOf(tokenModel.createdAt.toJavaLocalDateTime()))
        statement.setTimestamp(4, Timestamp.valueOf(tokenModel.expiresAt.toJavaLocalDateTime()))

        statement.executeUpdate()
        val generatedKeys = statement.generatedKeys
        if (generatedKeys.next()) {
            return@dbQuery generatedKeys.getInt(1)
        } else {
            throw Exception("Unable to retrieve the id of the newly inserted token")
        }
    }

    suspend fun findByToken(token: String): Token? = dbQuery { connection ->
        val statement = connection.prepareStatement(SELECT_TOKEN)
        statement.setString(1, token)
        val resultSet = statement.executeQuery()
        if (resultSet.next()) {
            return@dbQuery resultSet.toToken()
        } else {
            return@dbQuery null
        }
    }

    suspend fun findByUserId(userId: Int): Token? = dbQuery { connection ->
        val statement = connection.prepareStatement(SELECT_TOKEN_BY_USER_ID)
        statement.setInt(1, userId)
        val resultSet = statement.executeQuery()
        if (resultSet.next()) {
            return@dbQuery resultSet.toToken()
        } else {
            return@dbQuery null
        }
    }

    suspend fun update(tokenModel: Token): Boolean = dbQuery { connection ->
        val statement = connection.prepareStatement(UPDATE_TOKEN)
        statement.setString(1, tokenModel.token)
        statement.setTimestamp(2, Timestamp.valueOf(tokenModel.expiresAt.toJavaLocalDateTime()))
        statement.setTimestamp(3, Timestamp.valueOf(tokenModel.createdAt.toJavaLocalDateTime()))
        statement.setInt(4, tokenModel.userId)
        statement.executeUpdate() > 0
    }

    suspend fun deleteTokensForUser(userId: Int): Boolean = dbQuery { connection ->
        val statement = connection.prepareStatement(DELETE_TOKENS_FOR_USER)
        statement.setInt(1, userId)
        statement.executeUpdate() > 0
    }

    private fun ResultSet.toToken(): Token {
        return Token(
            id = getInt("id"),
            userId = getInt("user_id"),
            token = getString("token"),
            createdAt = getTimestamp("created_at").toLocalDateTime().toKotlinLocalDateTime(),
            expiresAt = getTimestamp("expires_at").toLocalDateTime().toKotlinLocalDateTime()
        )
    }
}

























