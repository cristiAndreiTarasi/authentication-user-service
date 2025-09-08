package example.com.routes.dtos

import example.com.PrivacyOptions

// Internal DTO used in schema layer
data class EventDto(
    val id: Int? = null,
    val title: String,
    val description: String?,
    val userId: Int,
    val username: String? = null,
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

// Request object (deserialize metadata form item)
@kotlinx.serialization.Serializable
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

// Response DTO
data class EventResponseDto(
    val id: Int,
    val title: String,
    val description: String?,
    val userId: Int,
    val username: String?,
    val privacyType: PrivacyOptions,
    val ticketPriceCents: Long,
    val categories: List<CategoryDto> = emptyList(),
    val tags: List<TagDto> = emptyList(),
    val thumbnailId: String?,
    val thumbnailData: String? = null, // base64 data URL optional
    val startsAt: kotlinx.datetime.LocalDateTime?,
    val status: String,
    val createdAt: kotlinx.datetime.LocalDateTime?,
    val updatedAt: kotlinx.datetime.LocalDateTime?
)