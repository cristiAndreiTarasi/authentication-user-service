package example.com.routes.dtos

import example.com.PrivacyOptions
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class StreamDto(
    val id: Int? = null,
    val title: String,
    val description: String? = null,
    val userId: Int,
    val username: String,
    val privacyType: PrivacyOptions,
    val ticketPrice: Float,
    var categories: List<CategoryDto> = emptyList(),
    var tags: List<TagDto> = emptyList(),
    val streamKey: String? = null,
    val publishTokenJti: String? = null,
    val tokenExpiresAt: LocalDateTime? = null,
    val startsAt: LocalDateTime? = null,
    val createdAt: LocalDateTime,
    val endedAt: LocalDateTime? = null,
    val status: String? = null,
    val thumbnailId: String? = null,
    var thumbnailData: String? = null,
)

@Serializable
data class CreateStreamRequestDto(
    val title: String,
    val description: String? = null,
    val userId: Int,
    val privacyType: PrivacyOptions,
    val ticketPrice: Float,
    val categories: List<CategoryDto> = emptyList(),
    val tags: List<TagDto> = emptyList(),
    val timezoneId: String,
    val thumbnailId: String? = null,
    val startsAt: LocalDateTime? = null
)


@Serializable
data class CreateStreamResponseDto(
    val streamId: Int? = null,
    val streamKey: String? = null,
    val publishToken: String? = null,
    val publishTokenJti: String? = null,
    val expiresAt: String? = null,
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
    val categories: List<CategoryDto> = emptyList(),
    val tags: List<TagDto> = emptyList(),
    val streamKey: String? = null,
    val publishTokenJti: String? = null,
    val tokenExpiresAt: LocalDateTime? = null,
    val startsAt: LocalDateTime? = null,
    val createdAt: LocalDateTime,
    val endedAt: LocalDateTime? = null,
    val status: String? = null,
    val thumbnailId: String? = null,
    var thumbnailData: String? = null
)