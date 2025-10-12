package example.com.schemas

import example.com.routes.dtos.CategoryDto
import example.com.schemas.queries.CategoryQueries
import example.com.schemas.queries.CategoryQueries.DELETE_EVENT_CATEGORY_BY_EVENT
import example.com.schemas.queries.CategoryQueries.DELETE_STREAM_CATEGORY
import example.com.schemas.queries.CategoryQueries.INSERT_CATEGORY
import example.com.schemas.queries.CategoryQueries.INSERT_EVENT_CATEGORY
import example.com.schemas.queries.CategoryQueries.INSERT_STREAM_CATEGORY
import example.com.schemas.queries.CategoryQueries.SELECT_ALL_CATEGORIES
import example.com.schemas.queries.CategoryQueries.SELECT_CATEGORIES_BY_EVENT_ID
import example.com.schemas.queries.CategoryQueries.SELECT_CATEGORIES_BY_STREAM_ID
import example.com.schemas.queries.CategoryQueries.SELECT_CATEGORY_BY_NAME
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import javax.sql.DataSource

class CategorySchema(private val dataSource: DataSource) {

    // --- event helpers (use an external connection to participate in transaction) ---
    // Non-suspending: caller must provide connection (transaction-managed)
    fun insertCategoriesForEvent(
        eventId: Int,
        categories: List<CategoryDto>,
        connection: Connection
    ) {
        connection.prepareStatement(INSERT_EVENT_CATEGORY).use { stmt ->
            for (category in categories) {
                val categoryId = category.id ?: insertCategory(name = category.name, connection = connection)
                stmt.setInt(1, eventId)
                stmt.setInt(2, categoryId)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }

    // Non-suspending: gets categories by event using provided connection
    fun getCategoriesByEventId(eventId: Int, connection: Connection): List<CategoryDto> {
        connection.prepareStatement(SELECT_CATEGORIES_BY_EVENT_ID).use { stmt ->
            stmt.setInt(1, eventId)
            stmt.executeQuery().use { rs ->
                val list = mutableListOf<CategoryDto>()
                while (rs.next()) {
                    list.add(CategoryDto(id = rs.getInt("id"), name = rs.getString("name"), imageUrl = rs.getString("image_url")))
                }
                return list
            }
        }
    }

    // ---------------------------
    // CATEGORY retrieval by STREAM
    // ---------------------------

    // Non-suspending, connection-aware variant for transactional callers (like StreamSchema)
    fun getCategoriesByStreamId(streamId: Int, connection: Connection): List<CategoryDto> {
        connection.prepareStatement(SELECT_CATEGORIES_BY_STREAM_ID).use { statement ->
            statement.setInt(1, streamId)
            statement.executeQuery().use { resultSet ->
                val categories = mutableListOf<CategoryDto>()
                while (resultSet.next()) {
                    categories.add(resultSet.toCategoryDataModel())
                }
                return categories
            }
        }
    }

    // Suspended, standalone variant that borrows a connection from the pool
    suspend fun getCategoriesByStreamId(streamId: Int): List<CategoryDto> = dbQuery { connection ->
        // reuse the connection-aware implementation to avoid duplication
        getCategoriesByStreamId(streamId, connection)
    }

    // --- standalone insertCategory (uses internal dbQuery and pool) ---
    suspend fun insertCategory(name: String): Int = dbQuery { connection ->
        insertCategory(name, connection)
    }

    // insertCategory using provided connection (used inside a transaction) - non-suspending
    fun insertCategory(name: String, connection: Connection): Int {
        // First check if exists
        connection.prepareStatement(SELECT_CATEGORY_BY_NAME).use { selectStatement ->
            selectStatement.setString(1, name)
            selectStatement.executeQuery().use { resultSet ->
                if (resultSet.next()) {
                    return resultSet.getInt("id")
                }
            }
        }

        // Insert
        connection.prepareStatement(INSERT_CATEGORY, Statement.RETURN_GENERATED_KEYS).use { insertedStatement ->
            insertedStatement.setString(1, name)
            insertedStatement.executeUpdate()
            insertedStatement.generatedKeys.use { generatedKeys ->
                if (generatedKeys.next()) {
                    return generatedKeys.getInt(1)
                } else {
                    throw Exception("Unable to retrieve the id of the newly inserted category")
                }
            }
        }
    }

    // --- stream helpers (existing behavior; accept connection so can be used transactionally) ---
    fun insertCategoriesForStream(streamId: Int, categories: List<CategoryDto>, connection: Connection) {
        connection.prepareStatement(INSERT_STREAM_CATEGORY).use { statement ->
            for (category in categories) {
                val categoryId = category.id ?: insertCategory(category.name, connection)
                statement.setInt(1, streamId)
                statement.setInt(2, categoryId)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    suspend fun deleteCategoriesForEvent(eventId: Int): Int = dbQuery { connection ->
        connection.prepareStatement(DELETE_EVENT_CATEGORY_BY_EVENT).use { st ->
            st.setInt(1, eventId)
            st.executeUpdate()
        }
    }

    suspend fun getAllCategories(): List<CategoryDto> = dbQuery { connection ->
        connection.prepareStatement(SELECT_ALL_CATEGORIES).use { statement ->
            statement.executeQuery().use { resultSet ->
                val categories = mutableListOf<CategoryDto>()
                while (resultSet.next()) {
                    categories.add(resultSet.toCategoryDataModel())
                }
                categories
            }
        }
    }

    // --- Delete categories for stream (transactional)
    suspend fun deleteCategoriesForStream(streamId: Int, categories: List<CategoryDto>): IntArray = dbQuery { connection ->
        connection.prepareStatement(DELETE_STREAM_CATEGORY).use { deleteStatement ->
            for (category in categories) {
                val categoryId = category.id ?: throw Exception("Category id missing for deletion")
                deleteStatement.setInt(1, streamId)
                deleteStatement.setInt(2, categoryId)
                deleteStatement.addBatch()
            }
            deleteStatement.executeBatch()
        }
    }

    private fun ResultSet.toCategoryDataModel(): CategoryDto {
        return CategoryDto(
            id = getInt("id"),
            name = getString("name"),
            imageUrl = getString("image_url")
        )
    }

    private suspend fun <T> dbQuery(block: suspend (Connection) -> T): T = withContext(Dispatchers.IO) {
        var conn: Connection? = null
        try {
            conn = dataSource.connection
            block(conn)
        } catch (e: SQLException) {
            throw RuntimeException("Database query failed: ${e.message}", e)
        } finally {
            try { conn?.close() } catch (_: Exception) {}
        }
    }
}
