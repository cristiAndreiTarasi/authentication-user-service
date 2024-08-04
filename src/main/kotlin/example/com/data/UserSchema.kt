package example.com.data

import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class ExposedUser(
    val id: Int? = null,
    val email: String,
    var password: String,
    val salt: String,
    val username: String,
    val passwordResetToken: String? = null,
    val passwordResetTokenExpiry: LocalDateTime? = null,
    val createdAt: LocalDateTime
)

object Users : Table() {
    val id = integer("id").autoIncrement()
    val email = varchar("email", 255).uniqueIndex()
    val password = varchar("password", 255)
    val salt = varchar("salt", 255)
    val username = varchar("username", 255)
    val passwordResetToken = varchar("password_reset_token", 512).nullable()
    val passwordResetTokenExpiry = datetime("password_reset_token_expiry").nullable()
    val createdAt = datetime("created_at")

    override val primaryKey = PrimaryKey(id)
}

class UserSchema(private val db: Database) {
    init {
        transaction(db) {
            SchemaUtils.create(Users)
        }
    }

    suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }

    suspend fun findUserByEmail(email: String) = dbQuery {
        Users.select { Users.email eq email }
            .map { toUser(it) }
            .singleOrNull()
    }

    suspend fun findUserByUsername(username: String) = dbQuery {
        Users.select { Users.username eq username }
            .map { toUser(it) }
            .singleOrNull()
    }

    suspend fun insertUser(user: ExposedUser): Int = dbQuery {
        Users.insert {
            it[email] = user.email
            it[password] = user.password
            it[salt] = user.salt
            it[username] = user.username
            it[createdAt] = user.createdAt.toJavaLocalDateTime()
        }[Users.id]
    }

    suspend fun deleteUser(id: Int) = dbQuery {
        Users.deleteWhere { Users.id eq id }
    }

    suspend fun getAllUsers(): List<ExposedUser> = dbQuery {
        Users.selectAll().map { toUser(it) }
    }

    suspend fun getUserById(id: Int): ExposedUser? {
        return dbQuery {
            Users.select { Users.id eq id }
                .map { toUser(it) }
                .singleOrNull()
        }
    }

    suspend fun updatePasswordResetToken(userId: Int, token: String, expiresAt: LocalDateTime): Boolean = dbQuery {
        Users.update({ Users.id eq userId }) {
            it[passwordResetToken] = token
            it[passwordResetTokenExpiry] = expiresAt.toJavaLocalDateTime()
        } > 0
    }

    suspend fun getUserByToken(token: String): ExposedUser? = dbQuery {
        Users.select { Users.passwordResetToken eq token }
            .map { toUser(it) }
            .singleOrNull()
    }

    suspend fun updateUserPassword(userId: Int, newPassword: String): Boolean = dbQuery {
        Users.update({ Users.id eq userId }) {
            it[password] = newPassword
        } > 0
    }

    private fun toUser(row: ResultRow): ExposedUser =
        ExposedUser(
            id = row[Users.id],
            email = row[Users.email],
            password = row[Users.password],
            salt = row[Users.salt],
            username = row[Users.username],
            passwordResetToken = row[Users.passwordResetToken],
            passwordResetTokenExpiry = row[Users.passwordResetTokenExpiry]?.toKotlinLocalDateTime(),
            createdAt = row[Users.createdAt].toKotlinLocalDateTime()
        )
}