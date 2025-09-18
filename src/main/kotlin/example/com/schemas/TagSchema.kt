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

class TagSchema(private val dbConnection: Connection) {
    // Insert tags for event using existing connection (transaction-friendly)
    suspend fun insertTagsForEvent(eventId: Int, tags: List<TagDto>, connection: Connection) {
        val stmt = connection.prepareStatement(INSERT_EVENT_TAG)
        try {
            for (tag in tags) {
                val normalized = normalizeTagName(tag.name)
                val tagId = tag.id ?: insertOrGetTagId(normalized, connection)
                stmt.setInt(1, eventId)
                stmt.setInt(2, tagId)
                stmt.addBatch()
            }
            stmt.executeBatch()
        } finally {
            stmt.close()
        }
    }

    suspend fun insertTagsForStream(streamId: Int, tags: List<TagDto>) = dbQuery { connection ->
        val stmt = connection.prepareStatement(INSERT_STREAM_TAG)
        try {
            for (tag in tags) {
                val normalized = normalizeTagName(tag.name)
                val tagId = tag.id ?: insertOrGetTagId(normalized, connection)
                stmt.setInt(1, streamId)
                stmt.setInt(2, tagId)
                stmt.addBatch()
            }
            stmt.executeBatch()
        } finally {
            stmt.close()
        }
    }

    suspend fun deleteTagsForEvent(eventId: Int): Int = dbQuery { connection ->
        val stmt = connection.prepareStatement(DELETE_EVENT_TAG_BY_EVENT)
        stmt.use { st ->
            st.setInt(1, eventId)
            st.executeUpdate()
        }
    }

    fun insertTagsForStream(streamId: Int, tags: List<TagDto>, connection: Connection) {
        val stmt = connection.prepareStatement(INSERT_STREAM_TAG)
        try {
            for (tag in tags) {
                val normalized = normalizeTagName(tag.name)
                val tagId = tag.id ?: insertOrGetTagId(normalized, connection)
                stmt.setInt(1, streamId)
                stmt.setInt(2, tagId)
                stmt.addBatch()
            }
            stmt.executeBatch()
        } finally {
            stmt.close()
        }
    }


    suspend fun getTagsByEventId(eventId: Int): List<TagDto> = dbQuery { connection ->
        val stmt = connection.prepareStatement(SELECT_TAGS_BY_EVENT_ID)
        try {
            stmt.setInt(1, eventId)
            val rs = stmt.executeQuery()
            val list = mutableListOf<TagDto>()
            while (rs.next()) {
                list.add(TagDto(id = rs.getInt("id"), name = rs.getString("name")))
            }
            rs.close()
            list
        } finally {
            stmt.close()
        }
    }

    suspend fun getTagsByStreamId(streamId: Int): List<TagDto> = dbQuery { connection ->
        val stmt = connection.prepareStatement(SELECT_TAGS_BY_STREAM_ID)
        try {
            stmt.setInt(1, streamId)
            val rs = stmt.executeQuery()
            val list = mutableListOf<TagDto>()
            while (rs.next()) {
                list.add(TagDto(id = rs.getInt("id"), name = rs.getString("name")))
            }
            rs.close()
            list
        } finally {
            stmt.close()
        }
    }

    suspend fun insertTag(name: String): Int = dbQuery { connection ->
        insertTag(name, connection)
    }

    // transactional insertTag (select -> insert if missing)
    // returns the id (existing or newly created)
    fun insertTag(name: String, connection: Connection): Int {
        val normalized = normalizeTagName(name)

        // try select first
        val selectStatement = connection.prepareStatement(SELECT_TAG_BY_NAME)
        try {
            selectStatement.setString(1, normalized)
            val rs = selectStatement.executeQuery()
            if (rs.next()) {
                val id = rs.getInt("id")
                rs.close()
                return id
            }
            rs.close()
        } finally {
            selectStatement.close()
        }

        // insert new tag
        val insertStatement = connection.prepareStatement(INSERT_TAG, Statement.RETURN_GENERATED_KEYS)
        try {
            insertStatement.setString(1, normalized)
            insertStatement.executeUpdate()
            val generatedKeys = insertStatement.generatedKeys
            if (generatedKeys.next()) {
                return generatedKeys.getInt(1)
            } else {
                throw Exception("Unable to retrieve the id of the newly inserted tag")
            }
        } finally {
            insertStatement.close()
        }
    }

    fun insertOrGetTagId(name: String, connection: Connection): Int = insertTag(name, connection)

    suspend fun getAllTags(): List<TagDto> = dbQuery { connection ->
        val stmt = connection.prepareStatement(SELECT_ALL_TAGS)
        try {
            val rs = stmt.executeQuery()
            val tags = mutableListOf<TagDto>()
            while (rs.next()) {
                tags.add(rs.toTagDataModel())
            }
            rs.close()
            tags
        } finally {
            stmt.close()
        }
    }

    // delete tags for stream - transactional variant: accepts TagDto list (uses provided connection)
    fun deleteTagsForStream(streamId: Int, tags: List<TagDto>, connection: Connection) {
        val stmt = connection.prepareStatement(DELETE_STREAM_TAG)
        try {
            for (tag in tags) {
                val normalized = normalizeTagName(tag.name)
                val tagId = getTagIdByName(normalized, connection)
                stmt.setInt(1, streamId)
                stmt.setInt(2, tagId)
                stmt.addBatch()
            }
            stmt.executeBatch()
        } finally {
            stmt.close()
        }
    }

    // Helper function to get tag id by name using provided connection
    private fun getTagIdByName(tag: String, connection: Connection): Int {
        val normalized = normalizeTagName(tag)
        val statement = connection.prepareStatement("SELECT id FROM tags WHERE name = ?")
        try {
            statement.setString(1, normalized)
            val rs = statement.executeQuery()
            if (rs.next()) {
                val id = rs.getInt("id")
                rs.close()
                return id
            } else {
                rs.close()
                throw Exception("Tag not found: $tag")
            }
        } finally {
            statement.close()
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
        try {
            block(dbConnection)
        } catch (e: SQLException) {
            throw RuntimeException("Database query failed: ${e.message}", e)
        }
    }
}
