package example.com.routes.dtos

import example.com.PrivacyOptions
import example.com.schemas.CategoryDto
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class CreateStreamRequest(
    val title: String,
    val description: String? = null,
    val userId: Int,
    val privacyType: PrivacyOptions,
    val ticketPrice: Float,
    val categories: List<CategoryDto>,
    val tags: List<String>,
    val timezoneId: String,
    val thumbnailId: String? = null,
    val startsAt: LocalDateTime? = null
)

@Serializable
data class CreateStreamResponse(
    val streamId: Int? = null,
    val message: String? = null,
    val isLive: Boolean? = null
)

@Serializable
data class DeleteStreamResponse(
    val message: String,
    val isLive: Boolean? = null
)

@Serializable
data class StreamResponse(
    val title: String,
    val description: String? = null,
    val userId: Int,
    val privacyType: PrivacyOptions,
    val ticketPrice: Float,
    val categories: List<CategoryDto>,
    val tags: List<String>,
    val createdAt: LocalDateTime,
    val thumbnailId: String? = null,
    var thumbnailBytes: List<Byte>? = null
)