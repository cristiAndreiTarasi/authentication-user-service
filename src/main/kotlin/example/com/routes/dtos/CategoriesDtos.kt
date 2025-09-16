package example.com.routes.dtos

import kotlinx.serialization.Serializable

@Serializable
data class CategoryDto(
    val id: Int? = null,
    val name: String,
    val imageUrl: String? = null
)

@Serializable
data class CreateCategoryRequestDto(
    val name: String,
    val imageUrl: String? = null
)
