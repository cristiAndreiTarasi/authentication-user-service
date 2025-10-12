package example.com.schemas

import example.com.config.LocalDateSerializer
import example.com.config.ObjectIdSerializer
import example.com.schemas.queries.UserQueries.DELETE_USER_BY_ID
import example.com.schemas.queries.UserQueries.INSERT_USER
import example.com.schemas.queries.UserQueries.SELECT_ALL_USERS
import example.com.schemas.queries.UserQueries.SELECT_LIVE_USERS
import example.com.schemas.queries.UserQueries.SELECT_USER_BY_EMAIL
import example.com.schemas.queries.UserQueries.SELECT_USER_BY_ID
import example.com.schemas.queries.UserQueries.SELECT_USER_BY_TOKEN
import example.com.schemas.queries.UserQueries.SELECT_USER_BY_USERNAME
import example.com.schemas.queries.UserQueries.SELECT_USER_FOLLOWERS
import example.com.schemas.queries.UserQueries.SELECT_USER_FOLLOWING
import example.com.schemas.queries.UserQueries.SELECT_USER_LIKES
import example.com.schemas.queries.UserQueries.UPDATE_PASSWORD_RESET_TOKEN
import example.com.schemas.queries.UserQueries.UPDATE_USER_BIO
import example.com.schemas.queries.UserQueries.UPDATE_USER_IMAGE_ID
import example.com.schemas.queries.UserQueries.UPDATE_USER_ISSTREAMING
import example.com.schemas.queries.UserQueries.UPDATE_USER_NAME
import example.com.schemas.queries.UserQueries.UPDATE_USER_OCCUPATION
import example.com.schemas.queries.UserQueries.UPDATE_USER_PASSWORD
import example.com.services.gridfs.GridFSService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime
import kotlinx.serialization.Serializable
import org.bson.types.ObjectId
import java.sql.*
import java.time.LocalDate
import javax.sql.DataSource

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
    private val dataSource: DataSource,
    private val gridFSService: GridFSService
) {
    // ----------------------
    // Public suspend methods (use the pool)
    // ----------------------
    suspend fun insertUser(user: ExposedUser): Int = dbQuery { connection ->
        connection.prepareStatement(INSERT_USER, Statement.RETURN_GENERATED_KEYS).use { statement ->
            statement.setString(1, user.email)
            statement.setString(2, user.password)
            statement.setString(3, user.salt)
            statement.setString(4, user.username)
            statement.setString(5, user.role)
            statement.setString(6, user.bio)
            statement.setString(7, user.occupation)
            statement.setTimestamp(8, Timestamp.valueOf(user.createdAt.toJavaLocalDateTime()))
            if (user.birthDate != null) statement.setDate(9, Date.valueOf(user.birthDate))
            else statement.setNull(9, Types.DATE)
            statement.setString(10, user.timezoneId)
            statement.setBoolean(11, user.isLive)

            statement.executeUpdate()
            statement.generatedKeys.use { generatedKeys ->
                if (generatedKeys.next()) {
                    val userId = generatedKeys.getInt(1)
                    println("User inserted with ID: $userId")
                    return@dbQuery userId
                } else {
                    throw Exception("Unable to retrieve the id of the newly inserted user")
                }
            }
        }
    }

    suspend fun findByEmail(email: String): ExposedUser? = dbQuery { connection ->
        connection.prepareStatement(SELECT_USER_BY_EMAIL).use { statement ->
            statement.setString(1, email)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    return@dbQuery rs.toUser()
                }
            }
        }
        null
    }

    suspend fun findByUsername(username: String): ExposedUser? = dbQuery { connection ->
        connection.prepareStatement(SELECT_USER_BY_USERNAME).use { statement ->
            statement.setString(1, username)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    return@dbQuery rs.toUser()
                }
            }
        }
        null
    }

    suspend fun findById(id: Int): ExposedUser? = dbQuery { connection ->
        connection.prepareStatement(SELECT_USER_BY_ID).use { statement ->
            statement.setInt(1, id)
            statement.executeQuery().use { rs ->
                if (rs.next()) return@dbQuery rs.toUser()
            }
        }
        null
    }

    suspend fun findByToken(token: String): ExposedUser? = dbQuery { connection ->
        connection.prepareStatement(SELECT_USER_BY_TOKEN).use { statement ->
            statement.setString(1, token)
            statement.executeQuery().use { rs ->
                if (rs.next()) return@dbQuery rs.toUser()
            }
        }
        null
    }

    suspend fun updatePasswordResetToken(userId: Int, token: String, expiresAt: LocalDateTime): Boolean = dbQuery { connection ->
        connection.prepareStatement(UPDATE_PASSWORD_RESET_TOKEN).use { statement ->
            statement.setString(1, token)
            statement.setTimestamp(2, Timestamp.valueOf(expiresAt.toJavaLocalDateTime()))
            statement.setInt(3, userId)
            statement.executeUpdate() > 0
        }
    }

    suspend fun getAllUsers(): List<ExposedUser> = dbQuery { connection ->
        connection.prepareStatement(SELECT_ALL_USERS).use { statement ->
            statement.executeQuery().use { rs ->
                return@dbQuery rs.toUsers()
            }
        }
    }

    suspend fun getLiveUsers(): List<ExposedUser> = dbQuery { connection ->
        connection.prepareStatement(SELECT_LIVE_USERS).use { statement ->
            statement.executeQuery().use { rs ->
                return@dbQuery rs.toUsers()
            }
        }
    }

    suspend fun getUserById(id: Int): ExposedUser? = dbQuery { connection ->
        connection.prepareStatement(SELECT_USER_BY_ID).use { statement ->
            statement.setInt(1, id)
            statement.executeQuery().use { rs -> return@dbQuery rs.toUsers().firstOrNull() }
        }
    }

    suspend fun updateUserPassword(userId: Int, newPassword: String): Boolean = dbQuery { connection ->
        connection.prepareStatement(UPDATE_USER_PASSWORD).use { statement ->
            statement.setString(1, newPassword)
            statement.setInt(2, userId)
            statement.executeUpdate() > 0
        }
    }

    suspend fun updateBio(id: Int, bio: String) = dbQuery { connection ->
        connection.prepareStatement(UPDATE_USER_BIO).use { statement ->
            statement.setString(1, bio)
            statement.setInt(2, id)
            statement.executeUpdate()
        }
    }

    suspend fun updateOccupation(id: Int, occupation: String) = dbQuery { connection ->
        connection.prepareStatement(UPDATE_USER_OCCUPATION).use { statement ->
            statement.setString(1, occupation)
            statement.setInt(2, id)
            statement.executeUpdate()
        }
    }

    suspend fun updateUsername(id: Int, username: String) = dbQuery { connection ->
        connection.prepareStatement(UPDATE_USER_NAME).use { statement ->
            statement.setString(1, username)
            statement.setInt(2, id)
            statement.executeUpdate()
        }
    }

    suspend fun updateUserImageId(userId: Int, imageId: String): Boolean = dbQuery { connection ->
        connection.prepareStatement(UPDATE_USER_IMAGE_ID).use { statement ->
            statement.setString(1, imageId)
            statement.setInt(2, userId)
            statement.executeUpdate() > 0
        }
    }

    suspend fun updateIsStreaming(id: Int, isLive: Boolean): Boolean = dbQuery { connection ->
        connection.prepareStatement(UPDATE_USER_ISSTREAMING).use { statement ->
            statement.setBoolean(1, isLive)
            statement.setInt(2, id)
            statement.executeUpdate() > 0
        }
    }

    /**
     * Delete user by id.
     *
     * This implementation:
     *  - executes delete within a short-lived connection from pool (no suspend while holding connection)
     *  - returns whether deleted and *after* commit the caller can remove the GridFS image using returned id
     */
    suspend fun deleteUser(id: Int): Boolean {
        // Run DB delete and fetch image id inside one borrowed connection
        val avatarId: String? = dbQuery { connection ->
            // Get the image id (if any) and delete the user
            var imageId: String? = null
            connection.prepareStatement("SELECT image_id FROM users WHERE id = ?").use { sel ->
                sel.setInt(1, id)
                sel.executeQuery().use { rs ->
                    if (rs.next()) imageId = rs.getString("image_id")
                }
            }

            val deleted = connection.prepareStatement(DELETE_USER_BY_ID).use { del ->
                del.setInt(1, id)
                del.executeUpdate() > 0
            }

            if (deleted) imageId else null
        }

        // delete GridFS outside DB connection / transaction
        if (avatarId != null) {
            try {
                gridFSService.deleteImage(ObjectId(avatarId))
            } catch (e: Exception) {
                // Non-fatal — we deleted DB row, but failed to remove file; log/ignore or re-try externally
                println("Warning: failed to delete avatar from GridFS: ${e.message}")
            }
        }

        return true
    }

    // Transactional overload: caller controls conn/commit/rollback. Returns image id (can be null).
    // This is NON-suspending to avoid misuse (caller manages transaction and must not call suspend while connection open).
    fun deleteUserTransactional(conn: Connection, id: Int): String? {
        var imageId: String? = null
        conn.prepareStatement("SELECT image_id FROM users WHERE id = ?").use { sel ->
            sel.setInt(1, id)
            sel.executeQuery().use { rs ->
                if (rs.next()) imageId = rs.getString("image_id")
            }
        }
        val deleted = conn.prepareStatement(DELETE_USER_BY_ID).use { del ->
            del.setInt(1, id)
            del.executeUpdate() > 0
        }
        return if (deleted) imageId else null
    }

    suspend fun getUserLikes(userId: Int): TallyDto = dbQuery { connection ->
        connection.prepareStatement(SELECT_USER_LIKES).use { statement ->
            statement.setInt(1, userId)
            statement.executeQuery().use { resultSet ->
                val likerIds = mutableListOf<Int>()
                while (resultSet.next()) {
                    likerIds.add(resultSet.getInt("liker_id"))
                }
                TallyDto(tally = likerIds.size, userIds = likerIds)
            }
        }
    }

    suspend fun getUserFollowers(userId: Int): TallyDto = dbQuery { connection ->
        connection.prepareStatement(SELECT_USER_FOLLOWERS).use { statement ->
            statement.setInt(1, userId)
            statement.executeQuery().use { resultSet ->
                val followerIds = mutableListOf<Int>()
                while (resultSet.next()) {
                    followerIds.add(resultSet.getInt("follower_id"))
                }
                TallyDto(tally = followerIds.size, userIds = followerIds)
            }
        }
    }

    suspend fun getUserFollowing(userId: Int): TallyDto = dbQuery { connection ->
        connection.prepareStatement(SELECT_USER_FOLLOWING).use { statement ->
            statement.setInt(1, userId)
            statement.executeQuery().use { resultSet ->
                val followingIds = mutableListOf<Int>()
                while (resultSet.next()) {
                    followingIds.add(resultSet.getInt("followed_id"))
                }
                TallyDto(tally = followingIds.size, userIds = followingIds)
            }
        }
    }

    // ----------------------
    // ResultSet -> model mappers
    // ----------------------
    private fun ResultSet.toUser(): ExposedUser {
        val birthDateSql = getDate("birth_date")
        val createdAtInstant = getTimestamp("created_at")!!.toLocalDateTime().toKotlinLocalDateTime()
        val imageIdStr = getString("image_id")
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
            passwordResetTokenExpiry = getTimestamp("password_reset_token_expiry")?.toLocalDateTime()?.toKotlinLocalDateTime(),
            imageId = imageIdStr?.let { ObjectId(it) },
            birthDate = birthDateSql?.toLocalDate(),
            createdAt = createdAtInstant,
            timezoneId = getString("timezone"),
            isLive = getBoolean("is_live")
        )
    }

    private fun ResultSet.toUsers(): List<ExposedUser> {
        val users = mutableListOf<ExposedUser>()
        while (next()) users.add(toUser())
        return users
    }

    // ----------------------
    // dbQuery helper (borrows connection from pool)
    // ----------------------
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
