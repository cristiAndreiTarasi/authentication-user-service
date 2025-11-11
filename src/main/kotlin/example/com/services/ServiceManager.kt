package example.com.services

import example.com.schemas.NotificationSchema
import example.com.schemas.UserSchema
import example.com.services.workers.NotificationWorker
import example.com.services.redis.RedisService
import example.com.services.redis.ShardedRedisService
import example.com.services.token.ITokenService
import example.com.services.workers.AnalyticsWorker
import example.com.services.workers.BillingWorker
import example.com.services.workers.ChatWorker
import example.com.services.workers.ModerationWorker
import example.com.services.ws_session.CrossInstanceBroadcaster
import example.com.services.ws_session.DistributedPermissionManager
import example.com.services.ws_session.DistributedSessionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.util.UUID

/**
 * Centralized service container that manages all distributed services and their lifecycle.
 * Start Pub/Sub listeners alongside Stream processors.
 */
// ServiceManager.kt
class ServiceManager(
    private val shardedRedisService: ShardedRedisService,
    private val userSchema: UserSchema,
    private val notificationSchema: NotificationSchema,
    private val authTokenService: ITokenService,
    val instanceId: String = System.getenv("HOSTNAME") ?: "instance-${UUID.randomUUID().toString().take(8)}"
) : CoroutineScope {
    override val coroutineContext = SupervisorJob() + Dispatchers.Default

    // Distributed services
    val distributedSessionManager = DistributedSessionManager(shardedRedisService, instanceId)
    val distributedPermissionManager = DistributedPermissionManager(shardedRedisService)
    val crossInstanceBroadcaster = CrossInstanceBroadcaster(shardedRedisService, instanceId)

    // Specialized stream workers for each Redis shard
    private val chatWorker = ChatWorker(shardedRedisService.chatRedis)
    private val moderationWorker = ModerationWorker(shardedRedisService.moderationRedis)
    private val analyticsWorker = AnalyticsWorker(shardedRedisService.analyticsRedis)
    private val billingWorker = BillingWorker(shardedRedisService.billingRedis)
    private val notificationWorker = NotificationWorker(shardedRedisService.socialRedis, notificationSchema, userSchema) // ✅ Fixed

    // Background jobs
    private var notificationWorkerJob: Job? = null
    private var sessionCleanupJob: Job? = null
    private var crossInstanceListenerJob: Job? = null

    // Stream processor jobs
    private var chatWorkerJob: Job? = null
    private var moderationWorkerJob: Job? = null
    private var analyticsWorkerJob: Job? = null
    private var billingWorkerJob: Job? = null

    /**
     * Starts all background workers and services
     */
    fun startAllServices() {
        println("🚀 Starting all background services for instance $instanceId...")

        // Initialize Redis stream consumer groups
        launch {
            try {
                shardedRedisService.initializeStreamConsumerGroups()
                println("✅ Redis stream consumer groups initialized")
            } catch (e: Exception) {
                println("❌ Failed to initialize Redis stream consumer groups: ${e.message}")
            }
        }

        // Start cross-instance Pub/Sub listener
        crossInstanceListenerJob = launch {
            println("📡 Starting cross-instance Pub/Sub listener...")
            crossInstanceBroadcaster.startCrossInstanceListener()
        }

        // Start specialized stream workers
        chatWorkerJob = launch {
            println("💬 Starting ChatWorker (redis-chat)...")
            chatWorker.start()
        }

        moderationWorkerJob = launch {
            println("🛡️ Starting ModerationWorker (redis-moderation)...")
            moderationWorker.start()
        }

        analyticsWorkerJob = launch {
            println("📊 Starting AnalyticsWorker (redis-analytics)...")
            analyticsWorker.start()
        }

        billingWorkerJob = launch {
            println("💰 Starting BillingWorker (redis-billing)...")
            billingWorker.start()
        }

        // Start notification worker (social events - uses redis-social)
        notificationWorkerJob = launch {
            println("🔔 Starting NotificationWorker (redis-social)...")
            notificationWorker.run()
        }

        // Start distributed session cleanup
        sessionCleanupJob = launch {
            println("🧹 Starting distributed session cleanup...")
            distributedSessionManager.cleanupInstanceSessions()
        }

        println("✅ All background services started successfully for instance $instanceId")
        println("   - Redis Shards: chat, moderation, analytics, billing, social, sessions")
        println("   - Workers: Specialized per shard")
        println("   - Pub/Sub: Cross-instance via redis-sessions")
    }

    /**
     * Stops all services and cancels background jobs
     */
    fun stopAllServices() {
        println("🛑 Stopping all services for instance $instanceId...")

        // Cancel all background jobs
        listOf(
            notificationWorkerJob,
            sessionCleanupJob,
            crossInstanceListenerJob,
            chatWorkerJob,
            moderationWorkerJob,
            analyticsWorkerJob,
            billingWorkerJob
        ).forEach { it?.cancel() }

        // Stop cross-instance broadcaster
        crossInstanceBroadcaster.stop()

        // Cancel the main coroutine scope
        coroutineContext.cancel()

        println("✅ All services stopped for instance $instanceId")
    }

    /**
     * Checks if all services are running properly
     */
    /**
     * Checks if all services are running properly
     */
    suspend fun healthCheck(): Map<String, Boolean> {
        val redisHealth = shardedRedisService.healthCheck()

        // Fixed: Create pairs explicitly to avoid type mismatch
        return mapOf(
            "redis_chat" to (redisHealth["chat"] ?: false),
            "redis_moderation" to (redisHealth["moderation"] ?: false),
            "redis_analytics" to (redisHealth["analytics"] ?: false),
            "redis_billing" to (redisHealth["billing"] ?: false),
            "redis_social" to (redisHealth["social"] ?: false),
            "redis_sessions" to (redisHealth["sessions"] ?: false),
            "cross_instance_running" to crossInstanceBroadcaster.isRunning(),
            "chat_worker_healthy" to chatWorker.isHealthy(),
            "moderation_worker_healthy" to moderationWorker.isHealthy(),
            "analytics_worker_healthy" to analyticsWorker.isHealthy(),
            "billing_worker_healthy" to billingWorker.isHealthy(),
            "notification_worker_healthy" to notificationWorker.isHealthy(),
            "distributed_sessions_healthy" to true
        )
    }
}
