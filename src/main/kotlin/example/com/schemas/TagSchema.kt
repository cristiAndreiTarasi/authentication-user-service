package example.com.schemas

import example.com.routes.dtos.TagDto
import example.com.schemas.queries.TagQueries.DELETE_EVENT_TAG_BY_EVENT
import example.com.schemas.queries.TagQueries.DELETE_STREAM_TAG
import example.com.schemas.queries.TagQueries.INSERT_EVENT_TAG
import example.com.schemas.queries.TagQueries.INSERT_STREAM_TAG
import example.com.schemas.queries.TagQueries.INSERT_TAG
import example.com.schemas.queries.TagQueries.SELECT_ALL_TAGS
import example.com.schemas.queries.TagQueries.SELECT_TAGS_BY_EVENT_ID
import example.com.schemas.queries.TagQueries.SELECT_TAGS_BY_STREAM_ID
import example.com.schemas.queries.TagQueries.SELECT_TAG_BY_NAME
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.sql.*
import javax.sql.DataSource

class TagSchema(private val dataSource: DataSource) {
    // Insert tags for event using existing connection (transaction-friendly) - non-suspending
    fun insertTagsForEvent(eventId: Int, tags: List<TagDto>, connection: Connection) {
        connection.prepareStatement(INSERT_EVENT_TAG).use { stmt ->
            for (tag in tags) {
                val normalized = normalizeTagName(tag.name)
                val tagId = tag.id ?: insertOrGetTagId(normalized, connection)
                stmt.setInt(1, eventId)
                stmt.setInt(2, tagId)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }

    // Non-suspending variant that participates in transaction (used by StreamSchema.create)
    fun insertTagsForStream(streamId: Int, tags: List<TagDto>, connection: Connection) {
        connection.prepareStatement(INSERT_STREAM_TAG).use { stmt ->
            for (tag in tags) {
                val normalized = normalizeTagName(tag.name)
                val tagId = tag.id ?: insertOrGetTagId(normalized, connection)
                stmt.setInt(1, streamId)
                stmt.setInt(2, tagId)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }

    // Standalone suspend variant that uses its own borrowed connection
    suspend fun insertTagsForStream(streamId: Int, tags: List<TagDto>) = dbQuery { connection ->
        connection.prepareStatement(INSERT_STREAM_TAG).use { stmt ->
            for (tag in tags) {
                val normalized = normalizeTagName(tag.name)
                val tagId = tag.id ?: insertOrGetTagId(normalized, connection)
                stmt.setInt(1, streamId)
                stmt.setInt(2, tagId)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }

    suspend fun deleteTagsForEvent(eventId: Int): Int = dbQuery { connection ->
        connection.prepareStatement(DELETE_EVENT_TAG_BY_EVENT).use { st ->
            st.setInt(1, eventId)
            st.executeUpdate()
        }
    }

    suspend fun getTagsByEventId(eventId: Int): List<TagDto> = dbQuery { connection ->
        connection.prepareStatement(SELECT_TAGS_BY_EVENT_ID).use { stmt ->
            stmt.setInt(1, eventId)
            stmt.executeQuery().use { rs ->
                val list = mutableListOf<TagDto>()
                while (rs.next()) {
                    list.add(TagDto(id = rs.getInt("id"), name = rs.getString("name")))
                }
                list
            }
        }
    }

    suspend fun getTagsByStreamId(streamId: Int): List<TagDto> = dbQuery { connection ->
        connection.prepareStatement(SELECT_TAGS_BY_STREAM_ID).use { stmt ->
            stmt.setInt(1, streamId)
            stmt.executeQuery().use { rs ->
                val list = mutableListOf<TagDto>()
                while (rs.next()) {
                    list.add(TagDto(id = rs.getInt("id"), name = rs.getString("name")))
                }
                list
            }
        }
    }

    suspend fun insertTag(name: String): Int = dbQuery { connection ->
        insertTag(name, connection)
    }

    // transactional insertTag (select -> insert if missing)
    // returns the id (existing or newly created) - non-suspending
    fun insertTag(name: String, connection: Connection): Int {
        val normalized = normalizeTagName(name)

        // try select first
        connection.prepareStatement(SELECT_TAG_BY_NAME).use { selectStatement ->
            selectStatement.setString(1, normalized)
            selectStatement.executeQuery().use { rs ->
                if (rs.next()) {
                    return rs.getInt("id")
                }
            }
        }

        // insert new tag
        connection.prepareStatement(INSERT_TAG, Statement.RETURN_GENERATED_KEYS).use { insertStatement ->
            insertStatement.setString(1, normalized)
            insertStatement.executeUpdate()
            insertStatement.generatedKeys.use { generatedKeys ->
                if (generatedKeys.next()) {
                    return generatedKeys.getInt(1)
                } else {
                    throw Exception("Unable to retrieve the id of the newly inserted tag")
                }
            }
        }
    }

    fun insertOrGetTagId(name: String, connection: Connection): Int = insertTag(name, connection)

    suspend fun getAllTags(): List<TagDto> = dbQuery { connection ->
        connection.prepareStatement(SELECT_ALL_TAGS).use { stmt ->
            stmt.executeQuery().use { rs ->
                val tags = mutableListOf<TagDto>()
                while (rs.next()) {
                    tags.add(rs.toTagDataModel())
                }
                tags
            }
        }
    }

    // delete tags for stream - transactional variant: accepts TagDto list (uses provided connection) - non-suspending
    fun deleteTagsForStream(streamId: Int, tags: List<TagDto>, connection: Connection) {
        connection.prepareStatement(DELETE_STREAM_TAG).use { stmt ->
            for (tag in tags) {
                val normalized = normalizeTagName(tag.name)
                val tagId = getTagIdByName(normalized, connection)
                stmt.setInt(1, streamId)
                stmt.setInt(2, tagId)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }

    // Helper function to get tag id by name using provided connection
    private fun getTagIdByName(tag: String, connection: Connection): Int {
        val normalized = normalizeTagName(tag)
        connection.prepareStatement("SELECT id FROM tags WHERE name = ?").use { statement ->
            statement.setString(1, normalized)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    return rs.getInt("id")
                } else {
                    throw Exception("Tag not found: $tag")
                }
            }
        }
    }

    private fun ResultSet.toTagDataModel(): TagDto {
        return TagDto(
            id = getInt("id"),
            name = getString("name")
        )
    }

    private fun normalizeTagName(raw: String): String {
        return raw.trim().trimStart('#')
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
