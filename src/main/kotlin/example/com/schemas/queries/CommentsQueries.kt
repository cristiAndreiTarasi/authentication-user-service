package example.com.schemas.queries

object CommentsQueries {
    const val INSERT_COMMENT = "INSERT INTO comments (stream_id, user_id, message, created_at) VALUES (?, ?, ?, ?)"

    const val SELECT_COMMENTS_BY_STREAM_ID = "SELECT * FROM comments WHERE stream_id = ?"

    const val DELETE_COMMENT = "DELETE FROM comments WHERE id = ?"
}