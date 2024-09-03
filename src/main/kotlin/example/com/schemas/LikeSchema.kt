package example.com.schemas

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement

@Serializable
data class LikeDataModel(
    val id: Int,
    val userId: Int,
    val likerId: Int,
    val streamId: Int
)

class LikeSchema(private val dbConnection: Connection) {
    companion object {
        private const val INSERT_LIKE = "INSERT INTO likes (user_id, liker_id, stream_id) VALUES (?, ?, ?)"
        private const val SELECT_LIKES_BY_USER_ID = "SELECT * FROM likes WHERE user_id = ?"
        private const val DELETE_LIKE = "DELETE FROM likes WHERE id = ?"
    }

    suspend fun addLike(userId: Int, likerId: Int, streamId: Int): Int = dbQuery { connection ->
        val statement = connection.prepareStatement(INSERT_LIKE, Statement.RETURN_GENERATED_KEYS)
        statement.setInt(1, userId)
        statement.setInt(2, likerId)
        statement.setInt(3, streamId)
        statement.executeUpdate()

        val generatedKeys = statement.generatedKeys
        if (generatedKeys.next()) {
            return@dbQuery generatedKeys.getInt(1)
        } else {
            throw Exception("Unable to retrieve the id of the newly inserted like")
        }
    }

    suspend fun getUserLikes(userId: Int): List<LikeDataModel> = dbQuery { connection ->
        val statement = connection.prepareStatement(SELECT_LIKES_BY_USER_ID)
        statement.setInt(1, userId)
        val resultSet = statement.executeQuery()

        val likes = mutableListOf<LikeDataModel>()
        while (resultSet.next()) {
            likes.add(resultSet.toLikeDataModel())
        }
        return@dbQuery likes
    }

    suspend fun removeLike(likeId: Int): Boolean = dbQuery { connection ->
        val statement = connection.prepareStatement(DELETE_LIKE)
        statement.setInt(1, likeId)
        statement.executeUpdate() > 0
    }

    private fun ResultSet.toLikeDataModel(): LikeDataModel {
        return LikeDataModel(
            id = getInt("id"),
            userId = getInt("user_id"),
            likerId = getInt("liker_id"),
            streamId = getInt("stream_id")
        )
    }

    private suspend fun <T> dbQuery(block: suspend (Connection) -> T): T = withContext(Dispatchers.IO) {
        try {
            block(dbConnection)
        } catch (e: SQLException) {
            throw RuntimeException("Database query failed: ${e.message}", e)
        }
    }
}
