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
    @SerialName("stream_id") val streamId: String?,
)

//{
//    "action": "on_publish",
//    "client_id": "2b746n03",
//    "ip": "172.18.0.8",
//    "vhost": "__defaultVhost__",
//    "app": "live",
//    "tcUrl": "rtmp://172.18.0.6:19350/live",
//    "stream": "livestream",
//    "param": "?token=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJhdWQiOiJodHRwOi8vMC4wLjAuMDo4MDgxL3VzZXJzIiwiaXNzIjoiaHR0cDovLzAuMC4wLjA6ODA4MSIsImV4cCI6MTc0NDAxNTcyNCwiaWF0IjoxNzQ0MDEyMTI0LCJ1c2VySWQiOiIxIiwicm9sZSI6Im93bmVyIn0.LHqWx74j6ni2OKM3WxVsX_iSv9Nc7DZxxzMUq7f0Iso",
//    "stream_url": "/live/livestream",
//    "stream_id": "vid-8p7707v"
//}
