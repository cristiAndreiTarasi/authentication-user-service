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

class CategorySchema(private val dbConnection: Connection) {

    // --- event helpers (use an external connection to participate in transaction) ---
    suspend fun insertCategoriesForEvent(
        eventId: Int,
        categories: List<CategoryDto>,
        connection: Connection
    ) {
        val stmt = connection.prepareStatement(INSERT_EVENT_CATEGORY)
        stmt.use { stmt ->
            for (category in categories) {
                val categoryId = category.id ?: insertCategory(category.name, connection)
                stmt.setInt(1, eventId)
                stmt.setInt(2, categoryId)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }

    suspend fun getCategoriesByEventId(eventId: Int, connection: Connection): List<CategoryDto> {
        val stmt = connection.prepareStatement(SELECT_CATEGORIES_BY_EVENT_ID)
        stmt.use { stmt ->
            stmt.setInt(1, eventId)
            val rs = stmt.executeQuery()
            val list = mutableListOf<CategoryDto>()
            while (rs.next()) {
                list.add(CategoryDto(id = rs.getInt("id"), name = rs.getString("name"), imageUrl = rs.getString("image_url")))
            }
            rs.close()
            return list
        }
    }

    // --- standalone insertCategory (uses internal dbQuery and dbConnection) ---
    suspend fun insertCategory(name: String): Int = dbQuery { connection ->
        insertCategory(name, connection)
    }

    // insertCategory using provided connection (used inside a transaction)
    fun insertCategory(name: String, connection: Connection): Int {
        // First check if exists
        val selectStatement = connection.prepareStatement(SELECT_CATEGORY_BY_NAME)
        try {
            selectStatement.setString(1, name)
            val resultSet = selectStatement.executeQuery()
            if (resultSet.next()) {
                val id = resultSet.getInt("id")
                resultSet.close()
                return id
            }
            resultSet.close()
        } finally {
            selectStatement.close()
        }

        // Insert
        val insertedStatement = connection.prepareStatement(INSERT_CATEGORY, Statement.RETURN_GENERATED_KEYS)
        try {
            insertedStatement.setString(1, name)
            insertedStatement.executeUpdate()
            val generatedKeys = insertedStatement.generatedKeys
            if (generatedKeys.next()) {
                return generatedKeys.getInt(1)
            } else {
                throw Exception("Unable to retrieve the id of the newly inserted category")
            }
        } finally {
            insertedStatement.close()
        }
    }

    // --- stream helpers (existing behavior) ---
    suspend fun insertCategoriesForStream(streamId: Int, categories: List<CategoryDto>, connection: Connection) {
        val statement = connection.prepareStatement(INSERT_STREAM_CATEGORY)
        try {
            for (category in categories) {
                val categoryId = category.id ?: insertCategory(category.name, connection)
                statement.setInt(1, streamId)
                statement.setInt(2, categoryId)
                statement.addBatch()
            }
            statement.executeBatch()
        } finally {
            statement.close()
        }
    }

    suspend fun deleteCategoriesForEvent(eventId: Int): Int = dbQuery { connection ->
        val stmt = connection.prepareStatement(DELETE_EVENT_CATEGORY_BY_EVENT)
        stmt.use { st ->
            st.setInt(1, eventId)
            st.executeUpdate()
        }
    }

    suspend fun getAllCategories(): List<CategoryDto> = dbQuery { connection ->
        val statement = connection.prepareStatement(SELECT_ALL_CATEGORIES)
        val resultSet = statement.executeQuery()
        val categories = mutableListOf<CategoryDto>()
        while (resultSet.next()) {
            categories.add(resultSet.toCategoryDataModel())
        }
        resultSet.close()
        statement.close()
        categories
    }

    suspend fun getCategoriesByStreamId(streamId: Int): List<CategoryDto> = dbQuery { connection ->
        val statement = connection.prepareStatement(SELECT_CATEGORIES_BY_STREAM_ID)
        try {
            statement.setInt(1, streamId)
            val resultSet = statement.executeQuery()
            val categories = mutableListOf<CategoryDto>()
            while (resultSet.next()) {
                categories.add(resultSet.toCategoryDataModel())
            }
            resultSet.close()
            return@dbQuery categories
        } finally {
            statement.close()
        }
    }

    suspend fun deleteCategoriesForStream(streamId: Int, categories: List<CategoryDto>): IntArray = dbQuery { connection ->
        val deleteStatement = connection.prepareStatement(DELETE_STREAM_CATEGORY)
        try {
            for (category in categories) {
                val categoryId = category.id ?: throw Exception("Category id missing for deletion")
                deleteStatement.setInt(1, streamId)
                deleteStatement.setInt(2, categoryId)
                deleteStatement.addBatch()
            }
            deleteStatement.executeBatch()
        } finally {
            deleteStatement.close()
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
        try {
            block(dbConnection)
        } catch (e: SQLException) {
            throw RuntimeException("Database query failed: ${e.message}", e)
        }
    }
}