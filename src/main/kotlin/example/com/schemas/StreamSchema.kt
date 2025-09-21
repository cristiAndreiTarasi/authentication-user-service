package example.com.schemas

import example.com.PrivacyOptions
import example.com.routes.dtos.StreamDto
import example.com.schemas.queries.StreamQueries
import example.com.services.gridfs.GridFSService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime
import kotlinx.datetime.toLocalDateTime
import org.bson.types.ObjectId
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.sql.Timestamp
import java.sql.Types
import java.util.Base64

class StreamSchema(
    private val dbConnection: Connection,
    private val categorySchema: CategorySchema,
    private val tagSchema: TagSchema,
    private val gridFSService: GridFSService
) {
    suspend fun create(stream: StreamDto): Int = dbQuery { connection ->
        val statement = connection.prepareStatement(StreamQueries.INSERT_STREAM, Statement.RETURN_GENERATED_KEYS)

        statement.setString(1, stream.title)
        statement.setString(2, stream.description)
        statement.setInt(3, stream.userId)
        statement.setString(4, stream.privacyType.displayName)
        statement.setFloat(5, stream.ticketPrice)
        statement.setString(6, stream.thumbnailId)

        // If startsAt is provided (kotlinx.datetime.LocalDateTime), convert to Instant assuming it's already in UTC
        if (stream.startsAt != null) {
            // you must be explicit about timezone of startsAt. If startsAt is a client-provided wall-time,
            // you should require clients to send an explicit timezone or an Instant. Here we assume it's UTC.
            val startsEpochMillis = stream.startsAt!!.toInstant(TimeZone.UTC).toEpochMilliseconds()
            statement.setTimestamp(7, java.sql.Timestamp.from(java.time.Instant.ofEpochMilli(startsEpochMillis)))
        } else {
            statement.setNull(7, Types.TIMESTAMP)
        }

        // createdAt was built using Clock.System.now(); store as absolute instant (UTC)
        val createdEpochMillis = stream.createdAt.toInstant(TimeZone.UTC).toEpochMilliseconds()
        statement.setTimestamp(8, java.sql.Timestamp.from(java.time.Instant.ofEpochMilli(createdEpochMillis)))

        statement.executeUpdate()

        val generatedKeys = statement.generatedKeys
        if (generatedKeys.next()) {
            val streamId = generatedKeys.getInt(1)

            // Insert categories and tags
            categorySchema.insertCategoriesForStream(streamId, stream.categories, connection)
            tagSchema.insertTagsForStream(streamId, stream.tags)

            return@dbQuery streamId
        } else {
            throw Exception("Unable to retrieve the id of the newly inserted stream")
        }
    }

    // Function to fetch a stream by ID
    suspend fun findById(streamId: Int): StreamDto? = dbQuery { connection ->
        val statement = connection.prepareStatement(StreamQueries.SELECT_STREAM_BY_ID)
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
        val statement = connection.prepareStatement(StreamQueries.SELECT_STREAMS_BY_USER_ID)
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

    suspend fun fetchStreamsCursor(cursor: LocalDateTime?, limit: Int): List<StreamDto> = dbQuery { connection ->
        val statement = connection.prepareStatement(StreamQueries.SELECT_ALL_STREAMS_CURSOR)
        // If no cursor is provided (initial load), we pass null; otherwise, pass the timestamp.
        if (cursor == null) {
            statement.setNull(1, Types.TIMESTAMP)
            statement.setNull(2, Types.TIMESTAMP)
        } else {
            val timestamp = Timestamp.valueOf(cursor.toJavaLocalDateTime())
            statement.setTimestamp(1, timestamp)
            statement.setTimestamp(2, timestamp)
        }
        statement.setInt(3, limit)

        val resultSet = statement.executeQuery()
        val streams = mutableListOf<StreamDto>()

        while (resultSet.next()) {
            val stream = resultSet.toStreamDataModel()

            // Fetch associated categories and tags
            stream.categories = categorySchema.getCategoriesByStreamId(stream.id!!)
            stream.tags = tagSchema.getTagsByStreamId(stream.id)

            // Fetch and encode thumbnail data
            stream.thumbnailData = stream.thumbnailId?.let { thumbnailId ->
                val thumbnailBytes = gridFSService.fetchImage(ObjectId(thumbnailId))
                if (thumbnailBytes.isNotEmpty()) {
                    "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(thumbnailBytes)
                } else {
                    null
                }
            }

            streams.add(stream)
        }

        return@dbQuery streams
    }

    // Function to fetch streams filtered by category
    suspend fun fetchStreamsByCategory(categoryId: Int, page: Int, pageSize: Int): List<StreamDto> = dbQuery { connection ->
        val statement = connection.prepareStatement(StreamQueries.SELECT_STREAMS_BY_CATEGORY_PAGINATED)
        statement.setInt(1, categoryId)
        statement.setInt(2, pageSize)
        statement.setInt(3, (page - 1) * pageSize)

        val resultSet = statement.executeQuery()
        val streams = mutableListOf<StreamDto>()

        while (resultSet.next()) {
            streams.add(resultSet.toStreamDataModelWithThumbnail())
        }
        return@dbQuery streams
    }

    // Function to fetch streams filtered by tag
    suspend fun fetchStreamsByTag(tag: String, page: Int, pageSize: Int): List<StreamDto> = dbQuery { connection ->
        val statement = connection.prepareStatement(StreamQueries.SELECT_STREAMS_BY_TAG_PAGINATED)
        statement.setString(1, tag)
        statement.setInt(2, pageSize)
        statement.setInt(3, (page - 1) * pageSize)

        val resultSet = statement.executeQuery()
        val streams = mutableListOf<StreamDto>()

        while (resultSet.next()) {
            streams.add(resultSet.toStreamDataModelWithThumbnail())
        }

        return@dbQuery streams
    }

    suspend fun findByStreamKey(streamKey: String): StreamDto? = dbQuery { connection ->
        // join users so username column is available to toStreamDataModel()
        val stmt = connection.prepareStatement(
            """
        SELECT s.*, u.username 
        FROM streams s
        LEFT JOIN users u ON u.id = s.user_id
        WHERE s.stream_key = ?
        """.trimIndent()
        )
        stmt.setString(1, streamKey)
        val rs = stmt.executeQuery()
        if (rs.next()) {
            val stream = rs.toStreamDataModel()
            stream.categories = categorySchema.getCategoriesByStreamId(stream.id!!)
            stream.tags = tagSchema.getTagsByStreamId(stream.id)
            return@dbQuery stream
        }
        return@dbQuery null
    }


    suspend fun setPublishInfo(
        streamId: Int,
        streamKey: String,
        publishTokenJti: String,
        tokenExpiresAtInstant: Instant
    ): Boolean = dbQuery { connection ->
        val stmt = connection.prepareStatement(
            "UPDATE streams SET stream_key = ?, publish_token_jti = ?, token_expires_at = ?, status = 'created' WHERE id = ?"
        )
        stmt.setString(1, streamKey)
        stmt.setString(2, publishTokenJti)

        // Convert kotlinx Instant -> java.sql.Timestamp (UTC)
        val epochMillis = tokenExpiresAtInstant.toEpochMilliseconds()
        stmt.setTimestamp(3, java.sql.Timestamp.from(java.time.Instant.ofEpochMilli(epochMillis)))

        stmt.setInt(4, streamId)
        stmt.executeUpdate() > 0
    }

    suspend fun markPublishingIfNotAlready(
        streamKey: String
    ): Boolean = dbQuery { connection ->
        val stmt = connection.prepareStatement(
            "UPDATE streams SET status = 'publishing' WHERE stream_key = ? AND status <> 'publishing'"
        )
        stmt.setString(1, streamKey)
        stmt.executeUpdate() > 0
    }

    suspend fun markEndedIfNotAlready(
        streamKey: String,
        endedAtInstant: Instant = Clock.System.now()
    ): Boolean = dbQuery { connection ->
        val stmt = connection.prepareStatement(
            // Only transition to 'ended' when currently 'publishing'
            "UPDATE streams SET status = 'ended', ended_at = ? WHERE stream_key = ? AND status = 'publishing'"
        )
        val epochMillis = endedAtInstant.toEpochMilliseconds()
        stmt.setTimestamp(1, java.sql.Timestamp.from(java.time.Instant.ofEpochMilli(epochMillis)))
        stmt.setString(2, streamKey)
        val updated = stmt.executeUpdate() > 0
        // optional logging: you may log using application logger higher up in route
        updated
    }


    private suspend fun ResultSet.toStreamDataModelWithThumbnail(): StreamDto {
        val stream = toStreamDataModel()

        stream.thumbnailData = stream.thumbnailId?.let { thumbnailId ->
            val thumbnailBytes = gridFSService.fetchImage(ObjectId(thumbnailId))

            if (thumbnailBytes.isNotEmpty()) {
                "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(thumbnailBytes)
            } else {
                null
            }
        }

        return stream
    }

    // Function to delete a stream
    suspend fun delete(streamId: Int): Boolean = dbQuery { connection ->
        val stream = findById(streamId)
        if (stream != null) {
            // Delete associated categories and tags first
            categorySchema.deleteCategoriesForStream(streamId, stream.categories)
            tagSchema.deleteTagsForStream(streamId, stream.tags, connection)
        }

        val statement = connection.prepareStatement(StreamQueries.DELETE_STREAM)
        statement.setInt(1, streamId)
        return@dbQuery statement.executeUpdate() > 0
    }

    suspend fun deleteByStreamKey(streamKey: String): Boolean = dbQuery { connection ->
        val stmt = connection.prepareStatement("DELETE FROM streams WHERE stream_key = ?")
        stmt.setString(1, streamKey)
        stmt.executeUpdate() > 0
    }

    // Helper function to map ResultSet to Stream object
    private fun ResultSet.toStreamDataModel(): StreamDto {
        val startsAtInstant = getTimestamp("starts_at")?.toInstant()
        val createdAtInstant = getTimestamp("created_at")!!.toInstant()
        // ended_at may be nullable
        val endedAtInstant = getTimestamp("ended_at")?.toInstant()

        return StreamDto(
            id = getInt("id"),
            title = getString("title"),
            description = getString("description"),
            userId = getInt("user_id"),
            username = getString("username"),
            privacyType = PrivacyOptions.entries.first { it.displayName == getString("privacy_type") },
            ticketPrice = getFloat("ticket_price"),
            categories = emptyList(),
            tags = emptyList(),
            startsAt = startsAtInstant?.let { Instant.fromEpochMilliseconds(it.toEpochMilli()).toLocalDateTime(TimeZone.UTC) },
            createdAt = Instant.fromEpochMilliseconds(createdAtInstant.toEpochMilli()).toLocalDateTime(TimeZone.UTC),
            thumbnailId = getString("thumbnail_id"),
            streamKey = getString("stream_key")
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