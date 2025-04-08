package example.com.schemas.queries

object CategoryQueries {
    const val INSERT_CATEGORY = "INSERT INTO categories (name) VALUES (?)"

    const val SELECT_CATEGORY_BY_NAME = "SELECT id FROM categories WHERE name = ?"

    const val SELECT_ALL_CATEGORIES = "SELECT * FROM categories"

    const val INSERT_STREAM_CATEGORY = "INSERT INTO stream_categories (stream_id, category_id) VALUES (?, ?)"

    const val DELETE_STREAM_CATEGORY = "DELETE FROM stream_categories WHERE stream_id = ? AND category_id = ?"

    const val COUNT_CATEGORY_USAGE = "SELECT COUNT(*) FROM stream_categories WHERE category_id = ?"

    const val DELETE_CATEGORY = "DELETE FROM categories WHERE id = ?"

    const val SELECT_CATEGORIES_BY_STREAM_ID = """
            SELECT c.* 
            FROM categories c 
            JOIN stream_categories sc ON c.id = sc.category_id 
            WHERE sc.stream_id = ?
        """
}