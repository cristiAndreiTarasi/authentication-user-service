package example.com.routes.dtos

import kotlinx.serialization.Serializable

@Serializable
data class TagDto(
    val id: Int? = null,
    val name: String
)