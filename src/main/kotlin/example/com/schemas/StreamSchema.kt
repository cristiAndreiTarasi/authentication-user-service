package example.com.schemas

import example.com.PrivacyOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.sql.Timestamp

@Serializable
data class StreamDto(
    val id: Int? = null,
    val title: String,
    val userId: Int,
    val privacyType: PrivacyOptions,
    val isTicketed: Boolean,
    var categories: List<CategoryDto>,
    var tags: List<String>,
    val createdAt: LocalDateTime,
    val thumbnailId: String? = null
)

class StreamSchema(
    private val dbConnection: Connection,
    private val categorySchema: CategorySchema,
    private val tagSchema: TagSchema
) {
    companion object {
        private const val INSERT_STREAM = """
            INSERT INTO streams 
            (title, user_id, privacy_type, is_ticketed, thumbnail_id, created_at) 
            VALUES (?, ?, ?, ?, ?, ?)
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
        private const val SELECT_STREAMS_BY_USER_ID = "SELECT * FROM streams WHERE user_id = ?"
        private const val DELETE_STREAM = "DELETE FROM streams WHERE id = ?"
    }

    suspend fun create(stream: StreamDto): Int = dbQuery { connection ->
        val statement = connection.prepareStatement(INSERT_STREAM, Statement.RETURN_GENERATED_KEYS)

        statement.setString(1, stream.title)
        statement.setInt(2, stream.userId)
        statement.setString(3, stream.privacyType.displayName)
        statement.setBoolean(4, stream.isTicketed)
        statement.setString(5, stream.thumbnailId)
        statement.setTimestamp(6, Timestamp.valueOf(stream.createdAt.toJavaLocalDateTime()))

        statement.executeUpdate()

        val generatedKeys = statement.generatedKeys
        if (generatedKeys.next()) {
            val streamId = generatedKeys.getInt(1)

            // Insert categories and tags separately
            categorySchema.insertCategoriesForStream(streamId, stream.categories, connection)
            tagSchema.insertTagsForStream(streamId, stream.tags)

            return@dbQuery streamId
        } else {
            throw Exception("Unable to retrieve the id of the newly inserted stream")
        }
    }


    // Function to fetch a stream by ID
    suspend fun findById(streamId: Int): StreamDto? = dbQuery { connection ->
        val statement = connection.prepareStatement(SELECT_STREAM_BY_ID)
        statement.setInt(1, streamId)

        val resultSet = statement.executeQuery()

        if (resultSet.next()) {
            val stream = resultSet.toStreamDataModel()

            stream.categories = categorySchema.getCategoriesByStreamId(stream.id!!)
            stream.tags = tagSchema.getTagsByStreamId(stream.id)

            return@dbQuery stream
        } else {
            return@dbQuery null
        }
    }

    suspend fun findByUserId(userId: Int): List<StreamDto> = dbQuery { connection ->
        val statement = connection.prepareStatement(SELECT_STREAMS_BY_USER_ID)
        statement.setInt(1, userId)

        val resultSet = statement.executeQuery()
        val streams = mutableListOf<StreamDto>()

        while (resultSet.next()) {
            val stream = resultSet.toStreamDataModel()

            // Fetch associated categories and tags
            stream.categories = categorySchema.getCategoriesByStreamId(stream.id!!)
            stream.tags = tagSchema.getTagsByStreamId(stream.id)

            streams.add(stream)
        }

        return@dbQuery streams
    }


    // Function to fetch all streams
    suspend fun findAll(): List<StreamDto> = dbQuery { connection ->
        val statement = connection.prepareStatement(SELECT_ALL_STREAMS)
        val resultSet = statement.executeQuery()

        val streams = mutableListOf<StreamDto>()

        while (resultSet.next()) {
            streams.add(resultSet.toStreamDataModel())
        }
        return@dbQuery streams
    }

    // Function to fetch streams filtered by category
    suspend fun findByCategory(categoryId: Int): List<StreamDto> = dbQuery { connection ->
        val statement = connection.prepareStatement(SELECT_STREAMS_BY_CATEGORY)
        statement.setInt(1, categoryId)

        val resultSet = statement.executeQuery()
        val streams = mutableListOf<StreamDto>()

        while (resultSet.next()) {
            streams.add(resultSet.toStreamDataModel())
        }
        return@dbQuery streams
    }

    // Function to fetch streams filtered by tag
    suspend fun findByTag(tag: String): List<StreamDto> = dbQuery { connection ->
        val statement = connection.prepareStatement(SELECT_STREAMS_BY_TAG)
        statement.setString(1, tag)

        val resultSet = statement.executeQuery()
        val streams = mutableListOf<StreamDto>()

        while (resultSet.next()) {
            streams.add(resultSet.toStreamDataModel())
        }

        return@dbQuery streams
    }

    // Function to delete a stream
    suspend fun delete(streamId: Int): Boolean = dbQuery { connection ->
        val stream = findById(streamId)
        if (stream != null) {
            // Delete associated categories and tags first
            categorySchema.deleteCategoriesForStream(streamId, stream.categories)
            tagSchema.deleteTagsForStream(streamId, stream.tags, connection)
        }

        val statement = connection.prepareStatement(DELETE_STREAM)
        statement.setInt(1, streamId)
        return@dbQuery statement.executeUpdate() > 0
    }

    // Helper function to map ResultSet to Stream object
    private fun ResultSet.toStreamDataModel(): StreamDto {
        return StreamDto(
            id = getInt("id"),
            title = getString("title"),
            userId = getInt("user_id"),
            privacyType = PrivacyOptions.entries.first { it.displayName == getString("privacy_type") },
            isTicketed = getBoolean("is_ticketed"),
            categories = emptyList(),  // Initially empty, will be filled in findById
            tags = emptyList(),        // Initially empty, will be filled in findById
            createdAt = getTimestamp("created_at").toLocalDateTime().toKotlinLocalDateTime(),
            thumbnailId = getString("thumbnail_id")
        )
    }

    private fun ResultSet.toStreams(): List<StreamDto> {
        val streams = mutableListOf<StreamDto>()
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