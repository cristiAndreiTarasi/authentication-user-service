package example.com.schemas

import example.com.PrivacyOptions
import example.com.routes.dtos.EventDto
import example.com.schemas.queries.EventQueries
import example.com.services.gridfs.GridFSService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime
import java.sql.*
import java.time.LocalDateTime

class EventSchema(
    private val dbConnection: Connection,
    private val categorySchema: CategorySchema,
    private val tagSchema: TagSchema,
    private val gridFSService: GridFSService
) {
    suspend fun createEvent(event: EventDto): Int = dbQuery { connection ->
        val stmt = connection.prepareStatement(EventQueries.INSERT_EVENT, Statement.RETURN_GENERATED_KEYS)

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
        val keys = stmt.generatedKeys
        if (keys.next()) {
            val eventId = keys.getInt(1)

            // Insert categories and tags for event
            // Implement insertCategoriesForEvent and insertTagsForEvent in categorySchema / tagSchema
            categorySchema.insertCategoriesForEvent(eventId, event.categories.orEmpty(), connection)
            tagSchema.insertTagsForEvent(eventId, event.tags.orEmpty(), connection)

            return@dbQuery eventId
        } else {
            throw Exception("Failed to create event")
        }
    }

    suspend fun findById(eventId: Int): EventDto? = dbQuery { connection ->
        val stmt = connection.prepareStatement(EventQueries.SELECT_EVENT_BY_ID)
        stmt.setInt(1, eventId)
        val rs = stmt.executeQuery()
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

            dto.categories = categorySchema.getCategoriesByEventId(dto.id!!, connection)
            dto.tags = tagSchema.getTagsByEventId(dto.id!!)

            // optional: populate thumbnailData the same way streams do
            dto
        } else null
    }

    suspend fun updateStatus(eventId: Int, status: String): Boolean = dbQuery { connection ->
        val stmt = connection.prepareStatement(EventQueries.UPDATE_EVENT_STATUS)
        stmt.setString(1, status)
        stmt.setInt(2, eventId)
        stmt.executeUpdate() > 0
    }

    suspend fun delete(eventId: Int): Boolean = dbQuery { connection ->
        val stmt = connection.prepareStatement(EventQueries.DELETE_EVENT)
        stmt.setInt(1, eventId)
        stmt.executeUpdate() > 0
    }

    private suspend fun <T> dbQuery(block: suspend (Connection) -> T): T = withContext(Dispatchers.IO) {
        try {
            block(dbConnection)
        } catch (e: SQLException) {
            throw RuntimeException("DB query failed: ${e.message}", e)
        }
    }
}
