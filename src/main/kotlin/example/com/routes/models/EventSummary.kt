package example.com.routes.models

data class EventSummary(
    val id: Int,
    val startsAt: kotlinx.datetime.LocalDateTime?,
    val userId: Int,
    val username: String
)