package example.com.services.redis

import example.com.services.socket.VisitorDto
import example.com.services.socket.VisitorUpdateDto

// Data model representing visitor data.
data class VisitorData(
    val visitorCount: Int,
    val visitorList: List<VisitorDto>
) {
    fun toDto(): VisitorUpdateDto {
        return VisitorUpdateDto(visitorCount = visitorCount, visitorList = visitorList)
    }
}
