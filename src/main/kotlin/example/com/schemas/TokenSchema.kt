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
data class Token(
    val id: Int? = null,
    val userId: Int,
    val token: String,
    var expiresAt: LocalDateTime,
    var createdAt: LocalDateTime
)

class TokenSchema(private val connection: Connection) {
    companion object {
        private const val CREATE_TABLE_TOKENS = """
            CREATE TABLE IF NOT EXISTS TOKENS (
                ID SERIAL PRIMARY KEY,
                USER_ID INT NOT NULL,
                TOKEN VARCHAR(512) NOT NULL,
                CREATED_AT TIMESTAMP NOT NULL,
                EXPIRES_AT TIMESTAMP NOT NULL,
                FOREIGN KEY (user_id) REFERENCES Users(id)
            );
        """
        private const val INSERT_TOKEN = "INSERT INTO tokens (user_id, token, created_at, expires_at) VALUES (?, ?, ?, ?)"
        private const val SELECT_TOKEN = "SELECT * FROM tokens WHERE token = ?"
        private const val SELECT_TOKEN_BY_USER_ID = "SELECT * FROM tokens WHERE user_id = ?"
        private const val UPDATE_TOKEN = "UPDATE tokens SET token = ?, expires_at = ? WHERE user_id = ?"
        private const val DELETE_TOKENS_FOR_USER = "DELETE FROM tokens WHERE user_id = ?"
    }

    init {
        val statement = connection.createStatement()
        statement.executeUpdate(CREATE_TABLE_TOKENS)
    }

    suspend fun <T> dbQuery(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    suspend fun create(tokenModel: Token): Int = dbQuery {
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

    suspend fun findByToken(token: String): Token? = dbQuery {
        val statement = connection.prepareStatement(SELECT_TOKEN)
        statement.setString(1, token)
        val resultSet = statement.executeQuery()
        if (resultSet.next()) {
            return@dbQuery resultSet.toToken()
        } else {
            return@dbQuery null
        }
    }

    suspend fun findByUserId(userId: Int): Token? = dbQuery {
        val statement = connection.prepareStatement(SELECT_TOKEN_BY_USER_ID)
        statement.setInt(1, userId)
        val resultSet = statement.executeQuery()
        if (resultSet.next()) {
            return@dbQuery resultSet.toToken()
        } else {
            return@dbQuery null
        }
    }

    suspend fun update(tokenModel: Token): Boolean = dbQuery {
        val statement = connection.prepareStatement(UPDATE_TOKEN)
        statement.setString(1, tokenModel.token)
        statement.setTimestamp(2, Timestamp.valueOf(tokenModel.expiresAt.toJavaLocalDateTime()))
        statement.setInt(3, tokenModel.userId)
        statement.executeUpdate() > 0
    }

    suspend fun deleteTokensForUser(userId: Int): Boolean = dbQuery {
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

























