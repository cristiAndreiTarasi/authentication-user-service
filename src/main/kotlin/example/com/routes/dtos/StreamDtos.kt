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
data class StreamResponseDto(
    val id: Int? = null,
    val title: String,
    val description: String? = null,
    val userId: Int,
    val username: String,
    val privacyType: PrivacyOptions,
    val ticketPrice: Float,
    val categories: List<CategoryDto>,
    val tags: List<String>,
    val createdAt: LocalDateTime,
    val thumbnailId: String? = null,
    var thumbnailData: String? = null
)

@Serializable
data class PaginatedStreamsResponse(
    val streams: List<StreamResponseDto>,
    val page: Int,
    val pageSize: Int,
    val totalStreams: Int
)