package example.com.schemas

import example.com.schemas.queries.TokenQueries.DELETE_TOKENS_FOR_USER
import example.com.schemas.queries.TokenQueries.INSERT_TOKEN
import example.com.schemas.queries.TokenQueries.SELECT_TOKEN
import example.com.schemas.queries.TokenQueries.SELECT_TOKEN_BY_USER_ID
import example.com.schemas.queries.TokenQueries.UPDATE_TOKEN
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime
import kotlinx.serialization.Serializable
import java.sql.*
import javax.sql.DataSource

@Serializable
data class Token(
    val id: Int? = null,
    val userId: Int,
    val token: String,
    var expiresAt: LocalDateTime,
    var createdAt: LocalDateTime
)

class TokenSchema(private val dataSource: DataSource) {
    suspend fun create(tokenModel: Token): Int = dbQuery { connection ->
        connection.prepareStatement(INSERT_TOKEN, Statement.RETURN_GENERATED_KEYS).use { statement ->
            statement.setInt(1, tokenModel.userId)
            statement.setString(2, tokenModel.token)
            statement.setTimestamp(3, Timestamp.valueOf(tokenModel.createdAt.toJavaLocalDateTime()))
            statement.setTimestamp(4, Timestamp.valueOf(tokenModel.expiresAt.toJavaLocalDateTime()))

            statement.executeUpdate()
            statement.generatedKeys.use { generatedKeys ->
                if (generatedKeys.next()) {
                    return@dbQuery generatedKeys.getInt(1)
                } else throw Exception("Unable to retrieve token id")
            }
        }
    }

    suspend fun findByToken(token: String): Token? = dbQuery { connection ->
        connection.prepareStatement(SELECT_TOKEN).use { stmt ->
            stmt.setString(1, token)
            stmt.executeQuery().use { rs ->
                if (rs.next()) rs.toToken() else null
            }
        }
    }

    suspend fun findByUserId(userId: Int): Token? = dbQuery { connection ->
        connection.prepareStatement(SELECT_TOKEN_BY_USER_ID).use { stmt ->
            stmt.setInt(1, userId)
            stmt.executeQuery().use { rs ->
                if (rs.next()) rs.toToken() else null
            }
        }
    }

    suspend fun update(tokenModel: Token): Boolean = dbQuery { connection ->
        connection.prepareStatement(UPDATE_TOKEN).use { stmt ->
            stmt.setString(1, tokenModel.token)
            stmt.setTimestamp(2, Timestamp.valueOf(tokenModel.expiresAt.toJavaLocalDateTime()))
            stmt.setTimestamp(3, Timestamp.valueOf(tokenModel.createdAt.toJavaLocalDateTime()))
            stmt.setInt(4, tokenModel.userId)
            stmt.executeUpdate() > 0
        }
    }

    // Non-transactional suspend delete
    suspend fun deleteTokensForUser(userId: Int): Boolean = dbQuery { connection ->
        connection.prepareStatement(DELETE_TOKENS_FOR_USER).use { stmt ->
            stmt.setInt(1, userId)
            stmt.executeUpdate() > 0
        }
    }

    // Transactional overload — caller manages commit/rollback (non-suspending)
    fun deleteTokensForUser(conn: Connection, userId: Int): Boolean {
        conn.prepareStatement(DELETE_TOKENS_FOR_USER).use { stmt ->
            stmt.setInt(1, userId)
            return stmt.executeUpdate() > 0
        }
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

    private suspend fun <T> dbQuery(block: suspend (Connection) -> T): T = withContext(Dispatchers.IO) {
        var conn: Connection? = null
        try {
            conn = dataSource.connection
            block(conn)
        } catch (e: SQLException) {
            throw RuntimeException("Database query failed: ${e.message}", e)
        } finally {
            try { conn?.close() } catch (_: Exception) {}
        }
    }
}

























