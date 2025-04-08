package example.com.routes.dtos

import kotlinx.serialization.Serializable

@Serializable
data class FollowerDataModel(
    val id: Int,
    val followerId: Int,
    val followedId: Int
)