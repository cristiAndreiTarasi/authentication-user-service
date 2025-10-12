package example.com.schemas

import example.com.PrivacyOptions
import example.com.StreamStatus
import example.com.routes.dtos.StreamDto
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
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toLocalDateTime
import org.bson.types.ObjectId
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.sql.Timestamp
import java.sql.Types
import java.util.Base64
import javax.sql.DataSource

class StreamSchema(
    private val dataSource: DataSource,
    private val categorySchema: CategorySchema,
    private val tagSchema: TagSchema,
    private val gridFSService: GridFSService
) {
    suspend fun create(stream: StreamDto): Int = dbQuery { connection ->
        connection.prepareStatement(StreamQueries.INSERT_STREAM, Statement.RETURN_GENERATED_KEYS).use { statement ->
            statement.setString(1, stream.title)
            statement.setString(2, stream.description)
            statement.setInt(3, stream.userId)
            statement.setString(4, stream.privacyType.displayName)
            statement.setFloat(5, stream.ticketPrice)
            statement.setString(6, stream.thumbnailId)

            if (stream.startsAt != null) {
                val startsEpochMillis = stream.startsAt!!.toInstant(TimeZone.UTC).toEpochMilliseconds()
                statement.setTimestamp(7, Timestamp.from(java.time.Instant.ofEpochMilli(startsEpochMillis)))
            } else {
                statement.setNull(7, Types.TIMESTAMP)
            }

            val createdEpochMillis = stream.createdAt.toInstant(TimeZone.UTC).toEpochMilliseconds()
            statement.setTimestamp(8, Timestamp.from(java.time.Instant.ofEpochMilli(createdEpochMillis)))

            statement.executeUpdate()
            statement.generatedKeys.use { generatedKeys ->
                if (generatedKeys.next()) {
                    val streamId = generatedKeys.getInt(1)

                    // Important: use transactional (connection-aware) category/tag insertion so they run in same conn
                    categorySchema.insertCategoriesForStream(streamId, stream.categories, connection)
                    // Use the connection-aware tag insert (non-suspending) to stay in same transaction
                    tagSchema.insertTagsForStream(streamId, stream.tags, connection)

                    return@dbQuery streamId
                } else {
                    throw Exception("Unable to retrieve the id of the newly inserted stream")
                }
            }
        }
    }

    suspend fun findById(streamId: Int): StreamDto? = dbQuery { connection ->
        connection.prepareStatement(StreamQueries.SELECT_STREAM_BY_ID).use { statement ->
            statement.setInt(1, streamId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) {
                    val stream = resultSet.toStreamDataModel()
                    // use the connection-aware category/tag fetchers (non-suspending)
                    stream.categories = categorySchema.getCategoriesByStreamId(stream.id!!, connection)
                    stream.tags = tagSchema.getTagsByStreamId(stream.id!!)
                    return@dbQuery stream
                } else {
                    return@dbQuery null
                }
            }
        }
    }

    suspend fun findByUserId(userId: Int): List<StreamDto> = dbQuery { connection ->
        connection.prepareStatement(StreamQueries.SELECT_STREAMS_BY_USER_ID).use { statement ->
            statement.setInt(1, userId)
            statement.executeQuery().use { resultSet ->
                val streams = mutableListOf<StreamDto>()
                while (resultSet.next()) {
                    val stream = resultSet.toStreamDataModel()
                    stream.categories = categorySchema.getCategoriesByStreamId(stream.id!!, connection)
                    stream.tags = tagSchema.getTagsByStreamId(stream.id!!)
                    streams.add(stream)
                }
                streams
            }
        }
    }

    suspend fun fetchStreamsCursor(cursor: LocalDateTime?, limit: Int): List<StreamDto> = dbQuery { connection ->
        connection.prepareStatement(StreamQueries.SELECT_ALL_STREAMS_CURSOR).use { statement ->
            if (cursor == null) {
                statement.setNull(1, Types.TIMESTAMP)
                statement.setNull(2, Types.TIMESTAMP)
            } else {
                val timestamp = Timestamp.valueOf(cursor.toJavaLocalDateTime())
                statement.setTimestamp(1, timestamp)
                statement.setTimestamp(2, timestamp)
            }
            statement.setInt(3, limit)

            statement.executeQuery().use { resultSet ->
                val streams = mutableListOf<StreamDto>()
                while (resultSet.next()) {
                    val stream = resultSet.toStreamDataModel()
                    stream.categories = categorySchema.getCategoriesByStreamId(stream.id!!, connection)
                    stream.tags = tagSchema.getTagsByStreamId(stream.id!!)
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
                streams
            }
        }
    }

    suspend fun fetchStreamsByCategory(categoryId: Int, page: Int, pageSize: Int): List<StreamDto> = dbQuery { connection ->
        connection.prepareStatement(StreamQueries.SELECT_STREAMS_BY_CATEGORY_PAGINATED).use { statement ->
            statement.setInt(1, categoryId)
            statement.setInt(2, pageSize)
            statement.setInt(3, (page - 1) * pageSize)
            statement.executeQuery().use { resultSet ->
                val streams = mutableListOf<StreamDto>()
                while (resultSet.next()) {
                    streams.add(resultSet.toStreamDataModelWithThumbnail())
                }
                streams
            }
        }
    }

    suspend fun fetchStreamsByTag(tag: String, page: Int, pageSize: Int): List<StreamDto> = dbQuery { connection ->
        connection.prepareStatement(StreamQueries.SELECT_STREAMS_BY_TAG_PAGINATED).use { statement ->
            statement.setString(1, tag)
            statement.setInt(2, pageSize)
            statement.setInt(3, (page - 1) * pageSize)
            statement.executeQuery().use { resultSet ->
                val streams = mutableListOf<StreamDto>()
                while (resultSet.next()) {
                    streams.add(resultSet.toStreamDataModelWithThumbnail())
                }
                streams
            }
        }
    }

    suspend fun findByStreamKey(streamKey: String): StreamDto? = dbQuery { connection ->
        connection.prepareStatement(StreamQueries.SELECT_STREAM_BY_STREAM_KEY).use { stmt ->
            stmt.setString(1, streamKey)
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    val stream = rs.toStreamDataModel()
                    stream.categories = categorySchema.getCategoriesByStreamId(stream.id!!, connection)
                    stream.tags = tagSchema.getTagsByStreamId(stream.id!!)
                    stream
                } else null
            }
        }
    }

    suspend fun setPublishInfo(
        streamId: Int,
        streamKey: String,
        publishTokenJti: String,
        tokenExpiresAtInstant: Instant
    ): Boolean = dbQuery { connection ->
        connection.prepareStatement(StreamQueries.UPDATE_STREAM_PUBLISH_INFO).use { stmt ->
            stmt.setString(1, streamKey)
            stmt.setString(2, publishTokenJti)
            val epochMillis = tokenExpiresAtInstant.toEpochMilliseconds()
            stmt.setTimestamp(3, Timestamp.from(java.time.Instant.ofEpochMilli(epochMillis)))
            stmt.setString(4, StreamStatus.CREATED.dbValue)
            stmt.setInt(5, streamId)
            stmt.executeUpdate() > 0
        }
    }

    suspend fun markPublishingIfNotAlready(streamKey: String): Boolean = dbQuery { connection ->
        val origAuto = connection.autoCommit
        try {
            connection.autoCommit = false

            connection.prepareStatement(StreamQueries.MARK_PUBLISHING).use { updateStmt ->
                updateStmt.setString(1, StreamStatus.PUBLISHING.dbValue)
                updateStmt.setString(2, streamKey)
                updateStmt.setString(3, StreamStatus.PUBLISHING.dbValue)
                updateStmt.executeQuery().use { rs ->
                    if (!rs.next()) {
                        connection.prepareStatement(StreamQueries.GET_STREAM_STATUS).use { checkStmt ->
                            checkStmt.setString(1, streamKey)
                            checkStmt.executeQuery().use { crs ->
                                val alreadyPublishing = if (crs.next()) {
                                    StreamStatus.fromDb(crs.getString("status")) == StreamStatus.PUBLISHING
                                } else {
                                    false
                                }
                                connection.commit()
                                return@dbQuery alreadyPublishing
                            }
                        }
                    }

                    val userId = rs.getInt("user_id")
                    connection.prepareStatement(StreamQueries.UPDATE_USER_LIVE_STATUS).use { setLiveStmt ->
                        setLiveStmt.setBoolean(1, true)
                        setLiveStmt.setInt(2, userId)
                        setLiveStmt.executeUpdate()
                    }
                    connection.commit()
                    return@dbQuery true
                }
            }
        } catch (ex: Exception) {
            try { connection.rollback() } catch (_: Exception) {}
            throw ex
        } finally {
            connection.autoCommit = origAuto
        }
    }

    suspend fun markEndedIfNotAlready(
        streamKey: String,
        endedAtInstant: Instant = Clock.System.now()
    ): Boolean = dbQuery { connection ->
        val origAuto = connection.autoCommit
        try {
            connection.autoCommit = false

            connection.prepareStatement(StreamQueries.MARK_ENDED).use { updateStmt ->
                updateStmt.setString(1, StreamStatus.ENDED.dbValue)
                val epochMillis = endedAtInstant.toEpochMilliseconds()
                updateStmt.setTimestamp(2, java.sql.Timestamp.from(java.time.Instant.ofEpochMilli(epochMillis)))
                updateStmt.setString(3, streamKey)
                updateStmt.setString(4, StreamStatus.PUBLISHING.dbValue)
                updateStmt.executeQuery().use { rs ->
                    if (!rs.next()) {
                        connection.commit()
                        return@dbQuery false
                    }
                    val userId = rs.getInt("user_id")
                    connection.prepareStatement(StreamQueries.COUNT_USER_PUBLISHING_STREAMS).use { countStmt ->
                        countStmt.setInt(1, userId)
                        countStmt.setString(2, StreamStatus.PUBLISHING.dbValue)
                        countStmt.executeQuery().use { crs ->
                            val publishingCount = if (crs.next()) crs.getInt("cnt") else 0
                            connection.prepareStatement(StreamQueries.UPDATE_USER_LIVE_STATUS).use { setLiveStmt ->
                                setLiveStmt.setBoolean(1, publishingCount > 0)
                                setLiveStmt.setInt(2, userId)
                                setLiveStmt.executeUpdate()
                            }
                        }
                    }
                    connection.commit()
                    return@dbQuery true
                }
            }
        } catch (ex: Exception) {
            try { connection.rollback() } catch (_: Exception) {}
            throw ex
        } finally {
            connection.autoCommit = origAuto
        }
    }

    suspend fun markTerminated(streamKey: String, reason: String): Boolean = dbQuery { connection ->
        val origAuto = connection.autoCommit
        try {
            connection.autoCommit = false

            connection.prepareStatement(StreamQueries.MARK_TERMINATED).use { updateStmt ->
                updateStmt.setString(1, StreamStatus.TERMINATED.dbValue)
                updateStmt.setString(2, reason)
                updateStmt.setString(3, streamKey)
                updateStmt.setString(4, StreamStatus.TERMINATED.dbValue)
                updateStmt.executeQuery().use { rs ->
                    if (!rs.next()) {
                        connection.commit()
                        return@dbQuery false
                    }
                    val userId = rs.getInt("user_id")
                    connection.prepareStatement(StreamQueries.COUNT_USER_PUBLISHING_STREAMS).use { countStmt ->
                        countStmt.setInt(1, userId)
                        countStmt.setString(2, StreamStatus.PUBLISHING.dbValue)
                        countStmt.executeQuery().use { crs ->
                            val publishingCount = if (crs.next()) crs.getInt("cnt") else 0
                            connection.prepareStatement(StreamQueries.UPDATE_USER_LIVE_STATUS).use { setLiveStmt ->
                                setLiveStmt.setBoolean(1, publishingCount > 0)
                                setLiveStmt.setInt(2, userId)
                                setLiveStmt.executeUpdate()
                            }
                        }
                    }
                    connection.commit()
                    return@dbQuery true
                }
            }
        } catch (ex: Exception) {
            try { connection.rollback() } catch (_: Exception) {}
            throw ex
        } finally {
            connection.autoCommit = origAuto
        }
    }

    suspend fun getStreamStatus(streamKey: String): StreamStatus? = dbQuery { connection ->
        connection.prepareStatement(StreamQueries.GET_STREAM_STATUS).use { stmt ->
            stmt.setString(1, streamKey)
            stmt.executeQuery().use { rs ->
                if (rs.next()) StreamStatus.fromDb(rs.getString("status")) else null
            }
        }
    }

    suspend fun delete(streamId: Int): Boolean = dbQuery { connection ->
        val origAuto = connection.autoCommit
        try {
            connection.autoCommit = false

            connection.prepareStatement(StreamQueries.GET_STREAM_FOR_DELETE).use { selectStmt ->
                selectStmt.setInt(1, streamId)
                selectStmt.executeQuery().use { srs ->
                    if (!srs.next()) {
                        connection.commit()
                        return@dbQuery false
                    }

                    val userId = srs.getInt("user_id")
                    val status = srs.getString("status") ?: "created"

                    connection.prepareStatement(StreamQueries.DELETE_STREAM_BY_ID).use { deleteStmt ->
                        deleteStmt.setInt(1, streamId)
                        val deleted = deleteStmt.executeUpdate() > 0
                        if (!deleted) {
                            connection.commit()
                            return@dbQuery false
                        }
                    }

                    if ("publishing".equals(status, ignoreCase = true)) {
                        connection.prepareStatement(StreamQueries.COUNT_USER_PUBLISHING_STREAMS).use { countStmt ->
                            countStmt.setInt(1, userId)
                            countStmt.setString(2, StreamStatus.PUBLISHING.dbValue)
                            countStmt.executeQuery().use { crs ->
                                val publishingCount = if (crs.next()) crs.getInt("cnt") else 0
                                connection.prepareStatement(StreamQueries.UPDATE_USER_LIVE_STATUS).use { setLiveStmt ->
                                    setLiveStmt.setBoolean(1, publishingCount > 0)
                                    setLiveStmt.setInt(2, userId)
                                    setLiveStmt.executeUpdate()
                                }
                            }
                        }
                    }

                    connection.commit()
                    return@dbQuery true
                }
            }
        } catch (ex: Exception) {
            try { connection.rollback() } catch (_: Exception) {}
            throw ex
        } finally {
            connection.autoCommit = origAuto
        }
    }

    suspend fun deleteByStreamKey(streamKey: String): Boolean = dbQuery { connection ->
        connection.prepareStatement(StreamQueries.DELETE_STREAM_BY_STREAM_KEY).use { stmt ->
            stmt.setString(1, streamKey)
            stmt.executeUpdate() > 0
        }
    }

    suspend fun fetchLiveStreams(limit: Int = 10): List<StreamDto> = dbQuery { connection ->
        connection.prepareStatement(StreamQueries.SELECT_LIVE_STREAMS).use { statement ->
            statement.setInt(1, limit)
            statement.executeQuery().use { rs ->
                val streams = mutableListOf<StreamDto>()
                while (rs.next()) {
                    val s = rs.toStreamDataModel()
                    streams.add(s)
                }
                streams
            }
        }
    }

    // Helper methods
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

    private fun ResultSet.toStreamDataModel(): StreamDto {
        val startsAtInstant = getTimestamp("starts_at")?.toInstant()
        val createdAtInstant = getTimestamp("created_at")!!.toInstant()
        // val endedAtInstant = getTimestamp("ended_at")?.toInstant() // currently unused

        val statusStr = try { getString("status") } catch (_: Exception) { null }
        val statusEnum = StreamStatus.fromDb(statusStr)

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
            startsAt = startsAtInstant?.let { Instant.fromEpochMilliseconds(it.toEpochMilli()).toLocalDateTime(kotlinx.datetime.TimeZone.UTC) },
            createdAt = Instant.fromEpochMilliseconds(createdAtInstant.toEpochMilli()).toLocalDateTime(kotlinx.datetime.TimeZone.UTC),
            thumbnailId = getString("thumbnail_id"),
            streamKey = getString("stream_key"),
            status = statusEnum
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
