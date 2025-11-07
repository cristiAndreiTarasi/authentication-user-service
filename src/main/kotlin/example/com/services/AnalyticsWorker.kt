package example.com.services

import example.com.services.redis.RedisService

class AnalyticsWorker(
    private val redisService: RedisService
) {
}