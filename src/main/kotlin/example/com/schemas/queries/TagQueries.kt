package example.com.schemas.queries

object TagQueries {
    const val INSERT_TAG = "INSERT INTO tags (name) VALUES (?)"
    const val SELECT_TAG_BY_NAME = "SELECT id, name FROM tags WHERE name = ?"
    const val SELECT_ALL_TAGS = "SELECT id, name FROM tags"
    const val INSERT_STREAM_TAG = "INSERT INTO stream_tags (stream_id, tag_id) VALUES (?, ?)"
    const val DELETE_STREAM_TAG = "DELETE FROM stream_tags WHERE stream_id = ? AND tag_id = ?"
    const val SELECT_TAGS_BY_STREAM_ID = """
        SELECT t.id, t.name
        FROM tags t
        JOIN stream_tags st ON t.id = st.tag_id
        WHERE st.stream_id = ?
    """

    // Event-specific mappings (optional: use these from EventSchema)
    const val INSERT_EVENT_TAG = "INSERT INTO event_tags (event_id, tag_id) VALUES (?, ?) ON CONFLICT DO NOTHING"
    const val SELECT_TAGS_BY_EVENT_ID = """
        SELECT t.id, t.name
        FROM tags t
        JOIN event_tags et ON t.id = et.tag_id
        WHERE et.event_id = ?
    """
}