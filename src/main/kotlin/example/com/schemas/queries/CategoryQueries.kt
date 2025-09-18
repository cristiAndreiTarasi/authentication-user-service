package example.com.schemas.queries

object CategoryQueries {
    const val SELECT_CATEGORY_BY_NAME = "SELECT id, name, image_url FROM categories WHERE name = ?"
    const val INSERT_CATEGORY = "INSERT INTO categories (name) VALUES (?)"
    const val SELECT_ALL_CATEGORIES = "SELECT id, name, image_url FROM categories"
    const val INSERT_STREAM_CATEGORY = "INSERT INTO stream_categories (stream_id, category_id) VALUES (?, ?)"
    const val SELECT_CATEGORIES_BY_STREAM_ID = """
        SELECT c.id, c.name, c.image_url
        FROM categories c
        JOIN stream_categories sc ON c.id = sc.category_id
        WHERE sc.stream_id = ?
    """
    const val DELETE_STREAM_CATEGORY = "DELETE FROM stream_categories WHERE stream_id = ? AND category_id = ?"

    // Event-specific mappings (optional: use these from EventSchema)
    const val INSERT_EVENT_CATEGORY = "INSERT INTO event_categories (event_id, category_id) VALUES (?, ?) ON CONFLICT DO NOTHING"
    const val SELECT_CATEGORIES_BY_EVENT_ID = """
        SELECT c.id, c.name, c.image_url
        FROM categories c
        JOIN event_categories ec ON c.id = ec.category_id
        WHERE ec.event_id = ?
    """
    const val DELETE_EVENT_CATEGORY_BY_EVENT = "DELETE FROM event_categories WHERE event_id = ?"
}