package example.com.routes.dtos

import kotlinx.serialization.Serializable

@Serializable
data class TagDto(
    val id: Int? = null,
    val name: String
)

@Serializable
data class TagCreateRequestDto(val name: String)

@Serializable
data class TagResponseDto(val id: Int, val name: String)
