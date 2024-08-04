package example.com.data

import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class Token(
    val id: Int? = null,
    val userId: Int,
    val token: String,
    var expiresAt: LocalDateTime,
    var createdAt: LocalDateTime
)

object Tokens : Table() {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id").references(Users.id)
    val token = varchar("token", 512)
    val createdAt = datetime("created_at")
    val expiresAt = datetime("expires_at")

    override val primaryKey = PrimaryKey(id)
}

class TokenSchema(private val db: Database) {
    init {
        transaction(db) {
            SchemaUtils.create(Users)
        }
    }

    suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }

    suspend fun insertRefreshToken(tokenModel: Token) = dbQuery {
        Tokens.insert {
            it[userId] = tokenModel.userId
            it[token] = tokenModel.token
            it[createdAt] = tokenModel.createdAt.toJavaLocalDateTime()
            it[expiresAt] = tokenModel.expiresAt.toJavaLocalDateTime()
        }[Tokens.id]
    }

    suspend fun getRefreshToken(token: String): Token? = dbQuery {
        Tokens.select { Tokens.token eq token }
            .map { toToken(it) }
            .singleOrNull()
    }

    suspend fun getRefreshTokenByUserId(userId: Int): Token? = dbQuery {
        Tokens.select { Tokens.userId eq userId }
            .map { toToken(it) }
            .singleOrNull()
    }

    suspend fun updateRefreshToken(tokenModel: Token) = dbQuery {
        Tokens.update({ Tokens.userId eq tokenModel.userId }) {
            it[token] = tokenModel.token
            it[expiresAt] = tokenModel.expiresAt.toJavaLocalDateTime()
        }
    }

    suspend fun deleteTokensForUser(userId: Int): Boolean = dbQuery {
        Tokens.deleteWhere { Tokens.userId eq userId } > 0
    }

    private fun toToken(row: ResultRow): Token =
        Token(
            row[Tokens.id],
            row[Tokens.userId],
            row[Tokens.token],
            row[Tokens.createdAt].toKotlinLocalDateTime(),
            row[Tokens.expiresAt].toKotlinLocalDateTime()
        )
}

























