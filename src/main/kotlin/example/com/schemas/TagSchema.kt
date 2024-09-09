package example.com.schemas

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.sql.*

@Serializable
data class TagDto(
    val id: Int? = null,
    val name: String
)

class TagSchema(private val dbConnection: Connection) {
    companion object {
        private const val INSERT_TAG = "INSERT INTO tags (name) VALUES (?)"
        private const val SELECT_TAG_BY_NAME = "SELECT id FROM tags WHERE name = ?"
        private const val SELECT_ALL_TAGS = "SELECT * FROM tags"
        private const val INSERT_STREAM_TAG = "INSERT INTO stream_tags (stream_id, tag_id) VALUES (?, ?)"
        private const val DELETE_STREAM_TAG = "DELETE FROM stream_tags WHERE stream_id = ? AND tag_id = ?"
        private const val COUNT_TAG_USAGE = "SELECT COUNT(*) FROM stream_tags WHERE tag_id = ?"
        private const val DELETE_TAG = "DELETE FROM tags WHERE id = ?"
        private const val SELECT_TAGS_BY_STREAM_ID = """
            SELECT t.* 
            FROM tags t 
            JOIN stream_tags st ON t.id = st.tag_id 
            WHERE st.stream_id = ?
        """
    }

    /*
    * Insert a new tag
    * */
    suspend fun insertTag(name: String,): Int = dbQuery { connection ->
        val selectStatement = connection.prepareStatement(SELECT_TAG_BY_NAME)
        selectStatement.setString(1, name)
        val resultSet = selectStatement.executeQuery()

        // If tag already exists, return it's id
        if (resultSet.next()) {
            return@dbQuery resultSet.getInt("id")
        }

        //If tag doesn't exist, insert it
        val insertStatement = connection.prepareStatement(INSERT_TAG, Statement.RETURN_GENERATED_KEYS)
        insertStatement.setString(1, name)
        insertStatement.executeUpdate()

        val generatedKeys = insertStatement.generatedKeys
        return@dbQuery if (generatedKeys.next()) {
            generatedKeys.getInt(1)
        } else {
            throw Exception("Unable to retrieve the id of the newly inserted tag")
        }
    }

    /*
    * Link tags to a stream
    * */
    suspend fun insertTagsForStream(streamId: Int, tags: List<String>) = dbQuery { connection ->
        val statement = connection.prepareStatement(INSERT_STREAM_TAG)

        for (tag in tags) {
            val tagId = insertTag(tag)

            statement.setInt(1, streamId)
            statement.setInt(2, tagId)
            statement.addBatch()
        }

        statement.executeBatch()
    }

    suspend fun getAllTags(): List<TagDto> = dbQuery { connection ->
        val statement = connection.prepareStatement(SELECT_ALL_TAGS)
        val resultSet = statement.executeQuery()

        val tags = mutableListOf<TagDto>()
        while (resultSet.next()) {
            tags.add(resultSet.toTagDataModel())
        }
        return@dbQuery tags
    }

    suspend fun getTagsByStreamId(streamId: Int): List<String> = dbQuery { connection ->
        val statement = connection.prepareStatement(SELECT_TAGS_BY_STREAM_ID)
        statement.setInt(1, streamId)

        val resultSet = statement.executeQuery()
        val tags = mutableListOf<String>()
        while (resultSet.next()) {
            tags.add(resultSet.getString("name"))
        }
        return@dbQuery tags
    }

    /*
    * Delete tags associated with a stream when the stream ends
    * */
    fun deleteTagsForStream(streamId: Int, tags: List<String>, connection: Connection) {
        val deleteStatement = connection.prepareStatement(DELETE_STREAM_TAG)

        for (tag in tags) {
            val tagId = getTagIdByName(tag, connection)

            // Remove the association between the stream and the tag
            deleteStatement.setInt(1, streamId)
            deleteStatement.setInt(2, tagId)
            deleteStatement.addBatch()
        }

        // Execute batch updates
        deleteStatement.executeBatch()
    }

    // Helper function to get the tag ID by name
    private fun getTagIdByName(tag: String, connection: Connection): Int {
        val statement = connection.prepareStatement("SELECT id FROM tags WHERE name = ?")
        statement.setString(1, tag)
        val resultSet = statement.executeQuery()
        return if (resultSet.next()) resultSet.getInt("id") else throw Exception("Tag not found: $tag")
    }

    private fun ResultSet.toTagDataModel(): TagDto {
        return TagDto(
            name = getString("name")
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
