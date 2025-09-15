package example.com.routes.dtos

import example.com.PrivacyOptions
import kotlinx.serialization.Serializable

@Serializable
data class EventDto(
    val id: Int? = null,
    val title: String,
    val description: String?,
    val userId: Int,
    val username: String? = null,
    val userAvatarUrl: String? = null,
    val privacyType: PrivacyOptions,
    val ticketPriceCents: Long = 0L,
    var categories: List<CategoryDto>? = null,
    var tags: List<TagDto>? = null,
    val thumbnailId: String? = null,
    val startsAt: kotlinx.datetime.LocalDateTime?,
    val status: String,
    val createdAt: kotlinx.datetime.LocalDateTime?,
    val updatedAt: kotlinx.datetime.LocalDateTime?
)

@Serializable
data class CreateEventRequest(
    val title: String,
    val description: String? = null,
    val userId: Int,
    val privacyType: PrivacyOptions,
    val ticketPriceCents: Long = 0L, // server expects cents
    val categories: List<CategoryDto> = emptyList(),
    val tags: List<TagDto> = emptyList(),
    val timezoneId: String = "UTC",
    val startsAt: String? = null // ISO string; parse later if present
)

@Serializable
data class EventResponseDto(
    val id: Int,
    val title: String,
    val description: String?,
    val userId: Int,
    val username: String?,
    val userOccupation: String?,
    val userAvatarUrl: String?,
    val privacyType: PrivacyOptions,
    val ticketPriceCents: Long,
    val categories: List<CategoryDto> = emptyList(),
    val tags: List<TagDto> = emptyList(),
    val thumbnailId: String?,
    val thumbnailUrl: String? = null,
    val startsAt: kotlinx.datetime.LocalDateTime?,
    val status: String,
    val createdAt: kotlinx.datetime.LocalDateTime?,
    val updatedAt: kotlinx.datetime.LocalDateTime?
)

// server/dto/EventSummaryDto.kt (serializable response)
@Serializable
data class EventSummaryDto(
    val id: Int,
    val userId: Int,
    val username: String,
    val userAvatarUrl: String?,
    val startsAt: String?
)

@Serializable
data class EventSummaryResponseDto(
    val id: Int,
    val title: String,
    val username: String? = null,
    val userAvatarUrl: String? = null,
    val startsAt: kotlinx.datetime.LocalDateTime? = null,
    val thumbnailUrl: String? = null,
    val likesCount: Int = 0
)