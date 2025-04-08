package example.com.routes.dtos

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class CommentDataModel(
    val id: Int,
    val streamId: Int,
    val userId: Int,
    val message: String,
    val createdAt: LocalDateTime
)