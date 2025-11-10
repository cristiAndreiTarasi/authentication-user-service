package example.com.services

/**
* Specialized worker for processing moderation events from Redis Streams
* Handles chat moderation, audit logs, and suspicious activity detection
*
* Separated from real-time event delivery which uses Pub/Sub
*/
class ModerationWorker {
    private var isHealthy: Boolean = false

    fun isHealthy(): Boolean = isHealthy
}
