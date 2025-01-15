package example.com.routes.dtos

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SrsHookPayload(
    val action: String,
    @SerialName("client_id") val clientId: String,
    val ip: String,
    val vhost: String,
    val app: String,
    val stream: String,
    val param: String,
    @SerialName("server_id") val serverId: String,
    @SerialName("stream_url") val streamUrl: String?,
    @SerialName("stream_id") val streamId: String?
)
