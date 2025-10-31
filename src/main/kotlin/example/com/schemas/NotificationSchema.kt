package example.com.schemas

import example.com.routes.dtos.NotificationDto
import java.sql.Connection
import java.sql.Statement
import java.sql.Timestamp
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NotificationSchema(private val dataSource: DataSource) {
    private suspend fun <T> dbQuery(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        val conn = dataSource.connection
        try {
            block(conn)
        } finally {
            try { conn.close() } catch (_: Throwable) {}
        }
    }

    suspend fun insertNotification(
        userId: Int,
        actorId: Int?,
        type: String,
        text: String?,
        metaJson: String? = "{}"
    ): Boolean = dbQuery { conn ->
        val sql = "INSERT INTO notifications (user_id, actor_id, type, text, meta) VALUES (?,?,?,?,?)"
        conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { stmt ->
            stmt.setInt(1, userId)
            if (actorId != null) stmt.setInt(2, actorId) else stmt.setNull(2, java.sql.Types.INTEGER)
            stmt.setString(3, type)
            stmt.setString(4, text)
            stmt.setString(5, metaJson)
            stmt.executeUpdate() > 0
        }
    }

    suspend fun fetchNotifications(
        userId: Int,
        limit: Int = 50,
        offset: Int = 0
    ): List<NotificationDto> = dbQuery { conn ->
        val sql = "SELECT id, user_id, actor_id, type, text, is_read, meta, created_at FROM notifications WHERE user_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?"
        conn.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, userId)
            stmt.setInt(2, limit)
            stmt.setInt(3, offset)
            val rs = stmt.executeQuery()
            val out = mutableListOf<NotificationDto>()
            while (rs.next()) {
                out.add(
                    NotificationDto(
                        id = rs.getInt("id"),
                        userId = rs.getInt("user_id"),
                        actorId = rs.getObject("actor_id")?.let { rs.getInt("actor_id") },
                        type = rs.getString("type"),
                        text = rs.getString("text"),
                        isRead = rs.getBoolean("is_read"),
                        meta = rs.getString("meta"),
                        createdAt = rs.getTimestamp("created_at").toString()
                    )
                )
            }
            out
        }
    }

    suspend fun markAsRead(userId: Int, notificationId: Int): Boolean = dbQuery { conn ->
        val sql = "UPDATE notifications SET is_read = TRUE WHERE id = ? AND user_id = ?"
        conn.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, notificationId)
            stmt.setInt(2, userId)
            stmt.executeUpdate() > 0
        }
    }

    suspend fun deleteNotification(userId: Int, notificationId: Int): Boolean = dbQuery { conn ->
        val sql = "DELETE FROM notifications WHERE id = ? AND user_id = ?"
        conn.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, notificationId)
            stmt.setInt(2, userId)
            stmt.executeUpdate() > 0
        }
    }

    suspend fun getUnreadCount(userId: Int): Int = dbQuery { conn ->
        val sql = "SELECT COUNT(*) as count FROM notifications WHERE user_id = ? AND is_read = FALSE"
        conn.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, userId)
            val rs = stmt.executeQuery()
            if (rs.next()) {
                rs.getInt("count")
            } else {
                0
            }
        }
    }

    suspend fun markAllAsRead(userId: Int): Boolean = dbQuery { conn ->
        val sql = "UPDATE notifications SET is_read = TRUE WHERE user_id = ? AND is_read = FALSE"
        conn.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, userId)
            stmt.executeUpdate() > 0
        }
    }
}
