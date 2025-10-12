package example.com.schemas

import example.com.PrivacyOptions
import example.com.routes.dtos.EventDto
import example.com.routes.models.EventSummary
import example.com.schemas.queries.EventQueries
import example.com.services.gridfs.GridFSService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime
import java.sql.*
import java.time.LocalDateTime
import javax.sql.DataSource

class EventSchema(
    private val dataSource: DataSource,
    private val categorySchema: CategorySchema,
    private val tagSchema: TagSchema,
) {
    suspend fun createEvent(event: EventDto): Int = dbQuery { connection ->
        connection.prepareStatement(EventQueries.INSERT_EVENT, Statement.RETURN_GENERATED_KEYS).use { stmt ->
            stmt.setString(1, event.title)
            stmt.setString(2, event.description)
            stmt.setInt(3, event.userId)
            stmt.setString(4, event.privacyType.displayName)
            stmt.setLong(5, event.ticketPriceCents)
            stmt.setString(6, event.thumbnailId)

            if (event.startsAt != null) {
                stmt.setTimestamp(7, Timestamp.valueOf(event.startsAt.toJavaLocalDateTime()))
            } else {
                stmt.setNull(7, Types.TIMESTAMP)
            }

            stmt.setString(8, event.status ?: "scheduled")
            stmt.setTimestamp(9, Timestamp.valueOf(event.createdAt?.toJavaLocalDateTime() ?: LocalDateTime.now()))
            stmt.setTimestamp(10, Timestamp.valueOf(event.updatedAt?.toJavaLocalDateTime() ?: LocalDateTime.now()))

            stmt.executeUpdate()
            stmt.generatedKeys.use { keys ->
                if (keys.next()) {
                    val eventId = keys.getInt(1)

                    // Insert categories and tags for event using the same connection
                    categorySchema.insertCategoriesForEvent(eventId, event.categories.orEmpty(), connection)
                    tagSchema.insertTagsForEvent(eventId, event.tags.orEmpty(), connection)

                    return@dbQuery eventId
                } else {
                    throw Exception("Failed to create event")
                }
            }
        }
    }

    suspend fun deleteEventCompletely(eventId: Int): Boolean = dbQuery { connection ->
        try {
            val origAuto = connection.autoCommit
            try {
                connection.autoCommit = false

                // delete event -> category links (transactional)
                connection.prepareStatement("/* placeholder */").use { /* noop - we use categorySchema.deleteCategoriesForEvent */ }

                categorySchema.deleteCategoriesForEvent(eventId)
                tagSchema.deleteTagsForEvent(eventId)

                connection.prepareStatement(EventQueries.DELETE_EVENT).use { deleteStmt ->
                    deleteStmt.setInt(1, eventId)
                    val rows = deleteStmt.executeUpdate()
                    if (rows == 0) throw Exception("Event deletion affected 0 rows")
                }

                connection.commit()
                true
            } catch (e: Exception) {
                try { connection.rollback() } catch (_: Exception) {}
                throw e
            } finally {
                try { connection.autoCommit = origAuto } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            throw e
        }
    }

    suspend fun fetchEventSummaries(
        limit: Int,
        sort: String,
        cursor: kotlinx.datetime.LocalDateTime? = null
    ): List<EventSummary> = dbQuery { connection ->
        val sql = when (sort) {
            "popular" -> EventQueries.SELECT_EVENTS_SUMMARY_BY_POPULARITY_DESC
            else -> EventQueries.SELECT_EVENTS_SUMMARY_BY_STARTS_ASC
        }

        connection.prepareStatement(sql).use { stmt ->
            if (sql == EventQueries.SELECT_EVENTS_SUMMARY_BY_STARTS_ASC) {
                if (cursor != null) {
                    val ts = Timestamp.valueOf(cursor.toJavaLocalDateTime())
                    stmt.setTimestamp(1, ts)
                } else {
                    stmt.setNull(1, Types.TIMESTAMP)
                }
                stmt.setInt(2, limit)
            } else {
                stmt.setInt(1, limit)
            }

            stmt.executeQuery().use { rs ->
                val items = mutableListOf<EventSummary>()
                while (rs.next()) {
                    items.add(
                        EventSummary(
                            id = rs.getInt("id"),
                            startsAt = rs.getTimestamp("starts_at")?.toLocalDateTime()?.toKotlinLocalDateTime(),
                            userId = rs.getInt("user_id"),
                            username = rs.getString("username")
                        )
                    )
                }
                items
            }
        }
    }

    suspend fun fetchEventsByUser(userId: Int): List<EventSummary> = dbQuery { connection ->
        connection.prepareStatement(EventQueries.SELECT_EVENTS_BY_USER).use { stmt ->
            stmt.setInt(1, userId)
            stmt.executeQuery().use { rs ->
                val out = mutableListOf<EventSummary>()
                while (rs.next()) {
                    out.add(
                        EventSummary(
                            id = rs.getInt("id"),
                            startsAt = rs.getTimestamp("starts_at")?.toLocalDateTime()?.toKotlinLocalDateTime(),
                            userId = rs.getInt("user_id"),
                            username = rs.getString("username")
                        )
                    )
                }
                out
            }
        }
    }

    suspend fun findById(eventId: Int): EventDto? = dbQuery { connection ->
        connection.prepareStatement(EventQueries.SELECT_EVENT_BY_ID).use { stmt ->
            stmt.setInt(1, eventId)
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    val dto = EventDto(
                        id = rs.getInt("id"),
                        title = rs.getString("title"),
                        description = rs.getString("description"),
                        userId = rs.getInt("user_id"),
                        username = rs.getString("username"),
                        privacyType = PrivacyOptions.entries.first { it.displayName == rs.getString("privacy_type") },
                        ticketPriceCents = rs.getLong("ticket_price"),
                        categories = emptyList(),
                        tags = emptyList(),
                        thumbnailId = rs.getString("thumbnail_id"),
                        startsAt = rs.getTimestamp("starts_at")?.toLocalDateTime()?.toKotlinLocalDateTime(),
                        status = rs.getString("status"),
                        createdAt = rs.getTimestamp("created_at")?.toLocalDateTime()?.toKotlinLocalDateTime(),
                        updatedAt = rs.getTimestamp("updated_at")?.toLocalDateTime()?.toKotlinLocalDateTime()
                    )

                    // populate categories and tags using connection-aware ops
                    dto.categories = categorySchema.getCategoriesByEventId(dto.id!!, connection)
                    // tagSchema.getTagsByEventId currently is suspend/dbQuery — we have a suspend function, but here we are inside the same dbQuery scope so we should call connection-aware tag retrieval if available.
                    // If tagSchema supplies a non-suspending getTagsByEventId with connection, call it; otherwise call suspend getTagsByEventId (which borrows its own connection) — for consistency we use the suspend one:
                    dto.tags = tagSchema.getTagsByEventId(dto.id!!)
                    dto
                } else null
            }
        }
    }

    suspend fun updateStatus(eventId: Int, status: String): Boolean = dbQuery { connection ->
        connection.prepareStatement(EventQueries.UPDATE_EVENT_STATUS).use { stmt ->
            stmt.setString(1, status)
            stmt.setInt(2, eventId)
            stmt.executeUpdate() > 0
        }
    }

    suspend fun delete(eventId: Int): Boolean = dbQuery { connection ->
        connection.prepareStatement(EventQueries.DELETE_EVENT).use { stmt ->
            stmt.setInt(1, eventId)
            stmt.executeUpdate() > 0
        }
    }

    private suspend fun <T> dbQuery(block: suspend (Connection) -> T): T = withContext(Dispatchers.IO) {
        var conn: Connection? = null
        try {
            conn = dataSource.connection
            block(conn)
        } catch (e: SQLException) {
            throw RuntimeException("DB query failed: ${e.message}", e)
        } finally {
            try { conn?.close() } catch (_: Exception) {}
        }
    }
}
