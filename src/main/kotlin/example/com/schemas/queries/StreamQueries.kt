package example.com.schemas.queries

object StreamQueries {
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

    const val SELECT_ALL_STREAMS_CURSOR = """
        SELECT s.*, u.username
        FROM streams s
        JOIN users u ON s.user_id = u.id
        /* If a cursor is provided, return only streams older than that */
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

    const val SELECT_STREAMS_BY_USER_ID = """
        SELECT s.*, u.username
        FROM streams s
        JOIN users u ON s.user_id = u.id
        WHERE s.user_id = ?
    """

    const val SELECT_LIVE_STREAMS = """
        SELECT s.*, u.username
        FROM streams s
        LEFT JOIN users u ON u.id = s.user_id
        WHERE s.status = 'publishing'
        ORDER BY s.created_at DESC
        LIMIT ?
    """

    const val DELETE_STREAM = "DELETE FROM streams WHERE id = ?"
}