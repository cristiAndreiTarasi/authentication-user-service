package example.com.schemas.queries

object EventQueries {
    const val INSERT_EVENT = """
        INSERT INTO events (title, description, user_id, privacy_type, ticket_price, thumbnail_id, starts_at, status, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """

    const val SELECT_EVENT_BY_ID = """
        SELECT e.*, u.username
        FROM events e
        JOIN users u ON u.id = e.user_id
        WHERE e.id = ?
    """

    const val SELECT_EVENTS_CURSOR = """
        SELECT e.*, u.username
        FROM events e
        JOIN users u ON u.id = e.user_id
        WHERE (e.starts_at > ? OR ? IS NULL)
        ORDER BY e.starts_at ASC
        LIMIT ?
    """

    const val UPDATE_EVENT_STATUS = """
        UPDATE events SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?
    """

    const val DELETE_EVENT = "DELETE FROM events WHERE id = ?"
}