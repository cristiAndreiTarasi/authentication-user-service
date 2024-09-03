package example.com.schemas

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.sql.Timestamp

@Serializable
data class CommentDataModel(
    val id: Int,
    val streamId: Int,
    val userId: Int,
    val message: String,
    val createdAt: LocalDateTime
)

class CommentSchema(private val dbConnection: Connection) {
    companion object {
        private const val INSERT_COMMENT = "INSERT INTO comments (stream_id, user_id, message, created_at) VALUES (?, ?, ?, ?)"
        private const val SELECT_COMMENTS_BY_STREAM_ID = "SELECT * FROM comments WHERE stream_id = ?"
        private const val DELETE_COMMENT = "DELETE FROM comments WHERE id = ?"
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

    suspend fun addComment(comment: CommentDataModel): Int = dbQuery { connection ->
        val statement = connection.prepareStatement(INSERT_COMMENT, Statement.RETURN_GENERATED_KEYS)
        statement.setInt(1, comment.streamId)
        statement.setInt(2, comment.userId)
        statement.setString(3, comment.message)
        statement.setTimestamp(4, Timestamp.valueOf(comment.createdAt.toJavaLocalDateTime()))
        statement.executeUpdate()

        val generatedKeys = statement.generatedKeys
        if (generatedKeys.next()) {
            return@dbQuery generatedKeys.getInt(1)
        } else {
            throw Exception("Unable to retrieve the id of the newly inserted comment")
        }
    }

    suspend fun findCommentsByStreamId(streamId: Int): List<CommentDataModel> = dbQuery { connection ->
        val statement = connection.prepareStatement(SELECT_COMMENTS_BY_STREAM_ID)
        statement.setInt(1, streamId)
        val resultSet = statement.executeQuery()

        val comments = mutableListOf<CommentDataModel>()
        while (resultSet.next()) {
            comments.add(resultSet.toCommentDataModel())
        }
        return@dbQuery comments
    }

    suspend fun deleteComment(commentId: Int): Boolean = dbQuery { connection ->
        val statement = connection.prepareStatement(DELETE_COMMENT)
        statement.setInt(1, commentId)
        statement.executeUpdate() > 0
    }

    private fun ResultSet.toCommentDataModel(): CommentDataModel {
        return CommentDataModel(
            id = getInt("id"),
            streamId = getInt("stream_id"),
            userId = getInt("user_id"),
            message = getString("message"),
            createdAt = getTimestamp("created_at").toLocalDateTime().toKotlinLocalDateTime()
        )
    }
}
