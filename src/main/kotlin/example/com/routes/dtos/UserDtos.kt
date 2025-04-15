package example.com.routes.dtos

import example.com.schemas.ExposedUser
import kotlinx.serialization.Serializable

@Serializable
data class AuthResponse(
    val newAccessToken: String? = null,
    val newRefreshToken: String? = null,
    val message: String? = null,
    val user: ExposedUser? = null
)

@Serializable
data class UploadImageResponse(val message: String, val imageId: String)

@Serializable
data class ProfileFieldUpdateResponse(
    val profileField: String
)

@Serializable
data class UpdateUsernameDto(val username: String)

@Serializable
data class UpdateBioDto(val bio: String)

@Serializable
data class UpdateOccupationDto(val occupation: String)

@Serializable
data class UsernameResponse(
    val username: String
)

