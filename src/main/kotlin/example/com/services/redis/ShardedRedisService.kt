package example.com.services.redis

import example.com.SocialEventType
import example.com.config.AppJson
import example.com.routes.dtos.LiveEvent
import example.com.routes.dtos.withDefaults
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json
import java.time.Instant

class ShardedRedisService(
    chatRedisUrl: String,
    moderationRedisUrl: String,
    analyticsRedisUrl: String,
    billingRedisUrl: String,
    socialRedisUrl: String,
    sessionsRedisUrl: String
) {
    // Individual Redis services for each shard
    val chatRedis: RedisService = RedisService(chatRedisUrl, RedisRole.CHAT)
    val moderationRedis: RedisService = RedisService(moderationRedisUrl, RedisRole.MODERATION)
    val analyticsRedis: RedisService = RedisService(analyticsRedisUrl, RedisRole.ANALYTICS)
    val billingRedis: RedisService = RedisService(billingRedisUrl, RedisRole.BILLING)
    val socialRedis: RedisService = RedisService(socialRedisUrl, RedisRole.SOCIAL)
    val sessionsRedis: RedisService = RedisService(sessionsRedisUrl, RedisRole.SESSIONS)

    private val json: Json = AppJson

    companion object {
        private const val MAX_HISTORY = 100
        private const val COUNTER_TTL_HOURS = 24L
        private const val SESSION_TTL_HOURS = 2L
    }

    // ==================================================
    // STREAM OPERATIONS (DURABLE, PERSISTENT)
    // ==================================================

    /**
     * Routes LiveEvent to appropriate Redis shard based on event category
     */
    suspend fun addToCategorizedStream(event: LiveEvent) {
        val safeEvent = event.withDefaults()
        val eventJson = json.encodeToString(PolymorphicSerializer(LiveEvent::class), safeEvent)

        when (event.getEventCategory()) {
            EventCategory.CHAT -> chatRedis.addToChatStream(event, eventJson)
            EventCategory.MODERATION -> moderationRedis.addToModerationStream(event, eventJson)
            EventCategory.ANALYTICS -> analyticsRedis.addToAnalyticsStream(event, eventJson)
            EventCategory.BILLING -> billingRedis.addToBillingStream(event, eventJson)
            EventCategory.SOCIAL -> socialRedis.addToSocialStream(event, eventJson)
            EventCategory.CONTROL -> sessionsRedis.addToControlStream(event, eventJson)
            EventCategory.REAL_TIME_ONLY -> {
                println("DEBUG: Real-time only event ${event::class.simpleName} not persisted to stream")
            }
        }
    }

    /**
     * Creates consumer groups for all streams if they don't exist
     */
    suspend fun initializeStreamConsumerGroups() {
        // Each Redis service initializes its own consumer groups
        listOf(chatRedis, moderationRedis, analyticsRedis, billingRedis, socialRedis, sessionsRedis)
            .forEach { redisService ->
                try {
                    redisService.initializeStreamConsumerGroups()
                } catch (e: Exception) {
                    println("WARN: Failed to initialize consumer groups for ${redisService.redisRole}: ${e.message}")
                }
            }
    }

    // ==================================================
    // PUB/SUB OPERATIONS (REAL-TIME, EPHEMERAL)
    // ==================================================

    /**
     * Publish real-time event to a room channel
     * Uses sessions Redis for Pub/Sub to ensure all instances receive messages
     */
    suspend fun publishToRoom(roomId: String, eventJson: String) {
        sessionsRedis.publishToRoom(roomId, eventJson)
    }

    /**
     * Publish user notification
     */
    suspend fun publishToUser(userId: String, eventJson: String) {
        sessionsRedis.publishToUser(userId, eventJson)
    }

    // ==================================================
    // ROOM HISTORY OPERATIONS (LIMITED RETENTION)
    // ==================================================

    /**
     * Adds chat messages to room history with size limit
     * Uses chat Redis for history storage
     */
    suspend fun addToHistory(roomId: String, event: LiveEvent) {
        if (event is LiveEvent.ChatMessage || event is LiveEvent.SystemMessage) {
            chatRedis.addToHistory(roomId, event)
        }
    }

    /**
     * Retrieves chat history for a room
     */
    suspend fun getRoomHistory(roomId: String): List<LiveEvent> {
        return chatRedis.getRoomHistory(roomId)
    }

    /**
     * Deletes room history (cleanup when room ends)
     */
    suspend fun deleteHistory(roomId: String) {
        chatRedis.deleteHistory(roomId)
    }

    // ==================================================
    // COUNTER OPERATIONS
    // ==================================================

    /**
     * Increments a counter value in Redis with TTL
     * Uses sessions Redis for counters
     */
    suspend fun incrementCounter(key: String, value: Long): Long {
        return sessionsRedis.incrementCounter(key, value)
    }

    /**
     * Increments user-specific like count for a room
     */
    suspend fun incrementUserLikeCount(roomId: String, userId: String, count: Long): Long {
        return sessionsRedis.incrementUserLikeCount(roomId, userId, count)
    }

    /**
     * Gets counter value from Redis
     */
    suspend fun getCounter(key: String): Long? {
        return sessionsRedis.getCounter(key)
    }

    /**
     * Cleans up all Redis data for a room when it ends
     */
    suspend fun deleteCounters(roomId: String) {
        sessionsRedis.deleteCounters(roomId)
    }

    // ==================================================
    // SESSION MANAGEMENT OPERATIONS
    // ==================================================

    /**
     * Room user operations with TTL
     * Uses sessions Redis for session management
     */
    suspend fun getRoomUserCount(roomId: String): Long {
        return sessionsRedis.getRoomUserCount(roomId)
    }

    suspend fun isUserInRoom(roomId: String, userId: String): Boolean {
        return sessionsRedis.isUserInRoom(roomId, userId)
    }

    suspend fun getRoomUsers(roomId: String): Set<String> {
        return sessionsRedis.getRoomUsers(roomId)
    }

    suspend fun addUserToRoom(roomId: String, userId: String) {
        sessionsRedis.addUserToRoom(roomId, userId)
    }

    suspend fun removeUserFromRoom(roomId: String, userId: String) {
        sessionsRedis.removeUserFromRoom(roomId, userId)
    }

    // ==================================================
    // HASH OPERATIONS (Session data)
    // ==================================================

    suspend fun hset(key: String, field: String, value: String) {
        sessionsRedis.hset(key, field, value)
    }

    suspend fun hget(key: String, field: String): String? {
        return sessionsRedis.hget(key, field)
    }

    suspend fun hgetall(key: String): Map<String, String> {
        return sessionsRedis.hgetall(key)
    }

    suspend fun hsetAll(key: String, map: Map<String, String>) {
        sessionsRedis.hsetAll(key, map)
    }

    // ==================================================
    // SET OPERATIONS
    // ==================================================

    suspend fun sadd(key: String, vararg members: String): Long {
        return sessionsRedis.sadd(key, *members)
    }

    suspend fun srem(key: String, vararg members: String): Long {
        return sessionsRedis.srem(key, *members)
    }

    suspend fun smembers(key: String): Set<String> {
        return sessionsRedis.smembers(key)
    }

    suspend fun sismember(key: String, member: String): Boolean {
        return sessionsRedis.sismember(key, member)
    }

    // ==================================================
    // KEY-VALUE OPERATIONS
    // ==================================================

    suspend fun set(key: String, value: String) {
        sessionsRedis.set(key, value)
    }

    suspend fun setex(key: String, seconds: Long, value: String) {
        sessionsRedis.setex(key, seconds, value)
    }

    suspend fun setnx(key: String, value: String): Boolean {
        return sessionsRedis.setnx(key, value)
    }

    suspend fun del(vararg keys: String): Long {
        return sessionsRedis.del(*keys)
    }

    suspend fun keys(pattern: String): List<String> {
        return sessionsRedis.keys(pattern)
    }

    suspend fun expire(key: String, seconds: Long): Boolean {
        return sessionsRedis.expire(key, seconds)
    }

    suspend fun get(key: String): String? {
        return sessionsRedis.get(key)
    }

    suspend fun exists(key: String): Long {
        return sessionsRedis.exists(key)
    }

    // ==================================================
    // HEALTH CHECKS
    // ==================================================

    /**
     * Checks connectivity to all Redis shards
     */
    suspend fun healthCheck(): Map<String, Boolean> {
        return mapOf(
            "chat" to chatRedis.ping(),
            "moderation" to moderationRedis.ping(),
            "analytics" to analyticsRedis.ping(),
            "billing" to billingRedis.ping(),
            "social" to socialRedis.ping(),
            "sessions" to sessionsRedis.ping()
        )
    }

    /**
     * Ping all Redis instances
     */
    suspend fun pingAll(): Boolean {
        return listOf(
            chatRedis,
            moderationRedis,
            analyticsRedis,
            billingRedis,
            socialRedis,
            sessionsRedis
        ).all { it.ping() }
    }

    // ==================================================
    // CLEANUP
    // ==================================================

    fun close() {
        listOf(chatRedis, moderationRedis, analyticsRedis, billingRedis, socialRedis, sessionsRedis)
            .forEach { it.close() }
    }
}

enum class RedisRole {
    CHAT, MODERATION, ANALYTICS, BILLING, SOCIAL, SESSIONS
}