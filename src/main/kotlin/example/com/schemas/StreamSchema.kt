package example.com.schemas

import example.com.plugins.routes.CategoryDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.sql.Timestamp

@Serializable
data class StreamDataModel(
    val id: Int? = null,
    val title: String,
    val description: String,
    val userId: Int,
    val startTime: String?,
    val endTime: String? = null,
    val isPublic: Boolean,
    val isTicketed: Boolean,
    var categories: List<CategoryDto>,
    var tags: List<String>,
    val createdAt: LocalDateTime,
    val thumbnailId: String? = null
)

class StreamSchema(private val dbConnection: Connection) {
    companion object {
        private const val INSERT_STREAM = """
            INSERT INTO streams 
            (title, description, user_id, start_time, end_time, is_public, is_ticketed, created_at) 
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """
        private const val SELECT_STREAM_BY_ID = "SELECT * FROM streams WHERE id = ?"
        private const val SELECT_ALL_STREAMS = "SELECT * FROM streams"
        private const val SELECT_STREAMS_BY_CATEGORY = "SELECT * FROM streams WHERE category_id = ?"
        private const val SELECT_STREAMS_BY_TAG = """
            SELECT s.* 
            FROM streams s 
            JOIN stream_tags t ON s.id = t.stream_id 
            WHERE t.tag = ?
        """
        private const val DELETE_STREAM = "DELETE FROM streams WHERE id = ?"
    }

    suspend fun create(stream: StreamDataModel): Int = dbQuery { connection ->
        val statement = connection.prepareStatement(INSERT_STREAM, Statement.RETURN_GENERATED_KEYS)

        statement.setString(1, stream.title)
        statement.setString(2, stream.description)
        statement.setInt(3, stream.userId)
        statement.setString(4, stream.startTime)
        statement.setString(5, stream.endTime)
        statement.setBoolean(6, stream.isPublic)
        statement.setBoolean(7, stream.isTicketed)
        statement.setTimestamp(8, Timestamp.valueOf(stream.createdAt.toJavaLocalDateTime()))

        statement.executeUpdate()

        val generatedKeys = statement.generatedKeys
        if (generatedKeys.next()) {
            val streamId = generatedKeys.getInt(1)

            // Insert categories and tags separately
            insertCategories(streamId, stream.categories, connection)
            insertTags(streamId, stream.tags, connection)

            return@dbQuery streamId
        } else {
            throw Exception("Unable to retrieve the id of the newly inserted stream")
        }
    }

    // Helper functions to insert categories and tags
    private fun insertCategories(streamId: Int, categories: List<CategoryDto>, connection: Connection) {
        val statement = connection.prepareStatement("INSERT INTO stream_categories (stream_id, category_id) VALUES (?, ?)")
        for (category in categories) {
            statement.setInt(1, streamId)
            statement.setInt(2, category.id)
            statement.addBatch()
        }
        statement.executeBatch()
    }

    private fun insertTags(streamId: Int, tags: List<String>, connection: Connection) {
        val statement = connection.prepareStatement("INSERT INTO stream_tags (stream_id, tag) VALUES (?, ?)")
        for (tag in tags) {
            statement.setInt(1, streamId)
            statement.setString(2, tag)
            statement.addBatch()
        }
        statement.executeBatch()
    }

    private fun getCategoriesByStreamId(streamId: Int, connection: Connection): List<CategoryDto> {
        val categories = mutableListOf<CategoryDto>()
        val statement = connection.prepareStatement("SELECT c.id, c.name FROM categories c JOIN stream_categories sc ON c.id = sc.category_id WHERE sc.stream_id = ?")
        statement.setInt(1, streamId)
        val resultSet = statement.executeQuery()

        while (resultSet.next()) {
            categories.add(CategoryDto(resultSet.getInt("id"), resultSet.getString("name")))
        }

        return categories
    }

    private fun getTagsByStreamId(streamId: Int, connection: Connection): List<String> {
        val tags = mutableListOf<String>()
        val statement = connection.prepareStatement("SELECT tag FROM stream_tags WHERE stream_id = ?")
        statement.setInt(1, streamId)
        val resultSet = statement.executeQuery()

        while (resultSet.next()) {
            tags.add(resultSet.getString("tag"))
        }

        return tags
    }

    // Function to fetch a stream by ID
    suspend fun findById(streamId: Int): StreamDataModel? = dbQuery { connection ->
        val statement = connection.prepareStatement(SELECT_STREAM_BY_ID)
        statement.setInt(1, streamId)

        val resultSet = statement.executeQuery()

        if (resultSet.next()) {
            val stream = resultSet.toStreamDataModel()

            stream.categories = getCategoriesByStreamId(stream.id!!, connection)
            stream.tags = getTagsByStreamId(stream.id, connection)

            return@dbQuery stream
        } else {
            return@dbQuery null
        }
    }

    // Function to fetch all streams
    suspend fun findAll(): List<StreamDataModel> = dbQuery { connection ->
        val statement = connection.prepareStatement(SELECT_ALL_STREAMS)
        val resultSet = statement.executeQuery()

        val streams = mutableListOf<StreamDataModel>()

        while (resultSet.next()) {
            streams.add(resultSet.toStreamDataModel())
        }
        return@dbQuery streams
    }

    // Function to fetch streams filtered by category
    suspend fun findByCategory(categoryId: Int): List<StreamDataModel> = dbQuery { connection ->
        val statement = connection.prepareStatement(SELECT_STREAMS_BY_CATEGORY)
        statement.setInt(1, categoryId)

        val resultSet = statement.executeQuery()
        val streams = mutableListOf<StreamDataModel>()

        while (resultSet.next()) {
            streams.add(resultSet.toStreamDataModel())
        }
        return@dbQuery streams
    }

    // Function to fetch streams filtered by tag
    suspend fun findByTag(tag: String): List<StreamDataModel> = dbQuery { connection ->
        val statement = connection.prepareStatement(SELECT_STREAMS_BY_TAG)
        statement.setString(1, tag)

        val resultSet = statement.executeQuery()
        val streams = mutableListOf<StreamDataModel>()

        while (resultSet.next()) {
            streams.add(resultSet.toStreamDataModel())
        }

        return@dbQuery streams
    }

    // Function to delete a stream
    suspend fun delete(streamId: Int): Boolean = dbQuery { connection ->
        val statement = connection.prepareStatement(DELETE_STREAM)

        statement.setInt(1, streamId)
        statement.executeUpdate() > 0
    }

    // Helper function to map ResultSet to Stream object
    private fun ResultSet.toStreamDataModel(): StreamDataModel {
        return StreamDataModel(
            id = getInt("id"),
            title = getString("title"),
            description = getString("description"),
            userId = getInt("user_id"),
            startTime = getString("start_time"),
            endTime = getString("end_time"),
            isPublic = getBoolean("is_public"),
            isTicketed = getBoolean("is_ticketed"),
            categories = emptyList(),  // Initially empty, will be filled in findById
            tags = emptyList(),        // Initially empty, will be filled in findById
            createdAt = getTimestamp("created_at").toLocalDateTime().toKotlinLocalDateTime(),
            thumbnailId = getString("thumbnail_id")
        )
    }

    private fun ResultSet.toStreams(): List<StreamDataModel> {
        val streams = mutableListOf<StreamDataModel>()
        while (next()) streams.add(toStreamDataModel())
        return streams
    }

    private suspend fun <T> dbQuery(block: suspend (Connection) -> T): T = withContext(Dispatchers.IO) {
        try {
            block(dbConnection)
        } catch (e: SQLException) {
            throw RuntimeException("Database query failed: ${e.message}", e)
        }
    }
}