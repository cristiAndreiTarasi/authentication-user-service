object StreamQueries {
    // Basic CRUD operations
    const val INSERT_STREAM = """
        INSERT INTO streams 
        (title, description, user_id, privacy_type, ticket_price, thumbnail_id, starts_at, created_at) 
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    """

    const val SELECT_STREAM_BY_ID = """
       SELECT s.*, u.username 
       FROM streams s
       JOIN users u ON s.user_id = u.id
       WHERE s.id = ? 
    """

    const val SELECT_STREAM_BY_STREAM_KEY = """
        SELECT s.*, u.username 
        FROM streams s
        LEFT JOIN users u ON u.id = s.user_id
        WHERE s.stream_key = ?
    """

    const val SELECT_STREAMS_BY_USER_ID = """
        SELECT s.*, u.username
        FROM streams s
        JOIN users u ON s.user_id = u.id
        WHERE s.user_id = ?
    """

    const val SELECT_ALL_STREAMS_CURSOR = """
        SELECT s.*, u.username
        FROM streams s
        JOIN users u ON s.user_id = u.id
        WHERE (CAST(? AS TIMESTAMP) IS NULL OR s.created_at < ?)
        ORDER BY s.created_at DESC
        LIMIT ?
    """

    const val SELECT_STREAMS_BY_CATEGORY_PAGINATED = """
        SELECT s.*, u.username
        FROM streams s
        JOIN users u ON s.user_id = u.id
        WHERE s.category_id = ? ORDER BY s.created_at DESC LIMIT ? OFFSET ?
    """

    const val SELECT_STREAMS_BY_TAG_PAGINATED = """
        SELECT s.*, u.username
        FROM streams s
        JOIN streams_tags t ON s.id = t.stream_id
        JOIN users u ON s.user_id = u.id
        WHERE t.tag = ? ORDER BY s.created_at DESC LIMIT ? OFFSET ?
    """

    const val SELECT_LIVE_STREAMS = """
        SELECT s.*, u.username
        FROM streams s
        LEFT JOIN users u ON u.id = s.user_id
        WHERE s.status = 'publishing'
        ORDER BY s.created_at DESC
        LIMIT ?
    """

    // Status update operations
    const val UPDATE_STREAM_PUBLISH_INFO = """
        UPDATE streams 
        SET stream_key = ?, publish_token_jti = ?, token_expires_at = ?, status = ? 
        WHERE id = ?
    """

    const val MARK_PUBLISHING = """
        UPDATE streams
        SET status = ?
        WHERE stream_key = ? AND status <> ?
        RETURNING user_id
    """

    const val MARK_ENDED = """
        UPDATE streams
        SET status = ?, ended_at = ?
        WHERE stream_key = ? AND status = ?
        RETURNING user_id
    """

    const val MARK_TERMINATED = """
        UPDATE streams 
        SET status = ?, 
            ended_at = NOW(),
            termination_reason = ?
        WHERE stream_key = ? AND status != ?
        RETURNING user_id
    """

    // Status check operations
    const val GET_STREAM_STATUS = "SELECT status FROM streams WHERE stream_key = ?"

    const val GET_STREAM_FOR_DELETE = "SELECT user_id, status FROM streams WHERE id = ?"

    // Count operations
    const val COUNT_USER_PUBLISHING_STREAMS = "SELECT COUNT(*) AS cnt FROM streams WHERE user_id = ? AND status = ?"

    // User status operations
    const val UPDATE_USER_LIVE_STATUS = "UPDATE users SET is_live = ? WHERE id = ?"

    // Delete operations
    const val DELETE_STREAM_BY_ID = "DELETE FROM streams WHERE id = ?"

    const val DELETE_STREAM_BY_STREAM_KEY = "DELETE FROM streams WHERE stream_key = ?"
}