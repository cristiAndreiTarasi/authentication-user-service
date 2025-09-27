package example.com.routes.models

import kotlinx.serialization.Serializable

@Serializable
enum class StreamStatus(val dbValue: String) {
    CREATED("created"),
    PUBLISHING("publishing"),
    ENDED("ended");

    companion object {
        fun fromDb(value: String?): StreamStatus {
            if (value == null) return CREATED
            return entries.firstOrNull { it.dbValue.equals(value, ignoreCase = true) } ?: CREATED
        }
    }
}