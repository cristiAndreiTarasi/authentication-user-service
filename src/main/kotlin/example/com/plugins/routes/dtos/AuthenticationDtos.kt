package example.com.plugins.routes.dtos

import kotlinx.serialization.Serializable

@Serializable
data class SignupRequestDto(
    val email: String,
    val password: String,
    val birthDate: String,
    val timezone: String
)

@Serializable
data class SigninRequestDto(val email: String, val password: String)

@Serializable
data class ResetPasswordRequest(val token: String, val newPassword: String)

@Serializable
data class RefreshTokenRequest(val refreshToken: String)

@Serializable
data class ForgotPasswordRequest(val email: String)

@Serializable
data class RefreshTokenResponse(val newAccessToken: String, val newRefreshToken: String, val message: String)

@Serializable
data class ForgotResponse(val message: String)

@Serializable
data class ResetResponse(val message: String)

@Serializable
data class SignoutRequest(val userId: Int)

@Serializable
data class SignoutResponse(val message: String)

@Serializable
data class AuthResponseDto(val accessToken: String, val refreshToken: String)