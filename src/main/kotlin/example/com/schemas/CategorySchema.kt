package example.com.schemas

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement

@Serializable
data class CategoryDto(
    val id: Int? = null,
    val name: String,
)

class CategorySchema(private val dbConnection: Connection) {
    companion object {
        private const val INSERT_CATEGORY = "INSERT INTO categories (name) VALUES (?)"
        private const val SELECT_CATEGORY_BY_NAME = "SELECT id FROM categories WHERE name = ?"
        private const val SELECT_ALL_CATEGORIES = "SELECT * FROM categories"
        private const val INSERT_STREAM_CATEGORY = "INSERT INTO stream_categories (stream_id, category_id) VALUES (?, ?)"
        private const val DELETE_STREAM_CATEGORY = "DELETE FROM stream_categories WHERE stream_id = ? AND category_id = ?"
        private const val COUNT_CATEGORY_USAGE = "SELECT COUNT(*) FROM stream_categories WHERE category_id = ?"
        private const val DELETE_CATEGORY = "DELETE FROM categories WHERE id = ?"
        private const val SELECT_CATEGORIES_BY_STREAM_ID = """
            SELECT c.* 
            FROM categories c 
            JOIN stream_categories sc ON c.id = sc.category_id 
            WHERE sc.stream_id = ?
        """
    }

    /*
    * Insert a new category
    * */
    suspend fun insertCategory(name: String): Int = dbQuery { connection ->
        val selectStatement = connection.prepareStatement(SELECT_CATEGORY_BY_NAME)
        selectStatement.setString(1, name)
        val resultSet = selectStatement.executeQuery()

        // If category exists, return it's id
        if (resultSet.next()) {
            return@dbQuery resultSet.getInt("id")
        }

        // If category doesn't exist, insert it
        val insertedStatement = connection.prepareStatement(INSERT_CATEGORY, Statement.RETURN_GENERATED_KEYS)
        insertedStatement.setString(1, name)
        insertedStatement.executeUpdate()

        val generatedKeys = insertedStatement.generatedKeys
        return@dbQuery if (generatedKeys.next()) {
            generatedKeys.getInt(1)
        } else {
            throw Exception("Unable to retrieve the id of the newly inserted category")
        }
    }

    /*
    * Link categories to a stream
    * */
    suspend fun insertCategoriesForStream(streamId: Int, categories: List<CategoryDto>, connection: Connection) {
        val statement = connection.prepareStatement(INSERT_STREAM_CATEGORY)

        for (category in categories) {
            val categoryId = insertCategory(category.name)
            statement.setInt(1, streamId)
            statement.setInt(2, categoryId)
            statement.addBatch()
        }

        statement.executeBatch()
    }

    suspend fun getAllCategories(): List<CategoryDto> = dbQuery { connection ->
        val statement = connection.prepareStatement(SELECT_ALL_CATEGORIES)
        val resultSet = statement.executeQuery()

        val categories = mutableListOf<CategoryDto>()
        while (resultSet.next()) {
            categories.add(resultSet.toCategoryDataModel())
        }
        return@dbQuery categories
    }

    suspend fun getCategoriesByStreamId(streamId: Int): List<CategoryDto> = dbQuery { connection ->
        val statement = connection.prepareStatement(SELECT_CATEGORIES_BY_STREAM_ID)
        statement.setInt(1, streamId)

        val resultSet = statement.executeQuery()
        val categories = mutableListOf<CategoryDto>()
        while (resultSet.next()) {
            categories.add(resultSet.toCategoryDataModel())
        }
        return@dbQuery categories
    }

    // Delete categories associated with a stream when the stream ends
    suspend fun deleteCategoriesForStream(streamId: Int, categories: List<CategoryDto>) = dbQuery { connection ->
        val deleteStatement = connection.prepareStatement(DELETE_STREAM_CATEGORY)

        for (category in categories) {
            // First, find the category ID
            val categoryId = category.id!!

            // Remove the association between the stream and the category
            deleteStatement.setInt(1, streamId)
            deleteStatement.setInt(2, categoryId)
            deleteStatement.addBatch()
        }

        // Execute batch updates
        deleteStatement.executeBatch()
    }

    private fun ResultSet.toCategoryDataModel(): CategoryDto {
        return CategoryDto(
            id = getInt("id"),
            name = getString("name"),
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
