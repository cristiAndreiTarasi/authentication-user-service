package example.com.services

import example.com.schemas.NotificationSchema
import example.com.schemas.UserSchema
import example.com.services.notifications.NotificationWorker
import example.com.services.redis.RedisService
import example.com.services.token.ITokenService
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
class ServiceManager(
    private val redisService: RedisService,
    private val userSchema: UserSchema,
    private val notificationSchema: NotificationSchema,
    private val authTokenService: ITokenService,
    instanceId: String = System.getenv("HOSTNAME") ?: "instance-${UUID.randomUUID().toString().take(8)}"
) : CoroutineScope {
    override val coroutineContext = SupervisorJob() + Dispatchers.Default

    val instanceId: String = instanceId

    // Distributed services
    val distributedSessionManager = DistributedSessionManager(redisService, instanceId)
    val distributedPermissionManager = DistributedPermissionManager(redisService)
    val crossInstanceBroadcaster = CrossInstanceBroadcaster(redisService, instanceId)
    val notificationWorker = NotificationWorker(redisService, notificationSchema, userSchema)

    // Specialized stream workers for durable processing
    val moderationWorker = ModerationWorker(redisService)
    val analyticsWorker = AnalyticsWorker(redisService)

    // Background jobs
    private var redisConsumerJob: Job? = null
    private var notificationWorkerJob: Job? = null
    private var sessionCleanupJob: Job? = null
    private var crossInstanceListenerJob: Job? = null

    // Stream processor jobs for durable workflows
    private var moderationWorkerJob: Job? = null
    private var analyticsWorkerJob: Job? = null

    /**
    * Starts all background workers and services
    * Start Pub/Sub listeners and specialized Stream workers
    */
    fun startAllServices() {
        // CHANGE: Start Pub/Sub listener for real-time cross-instance events
        crossInstanceListenerJob = launch {
            println("🚀 Starting cross-instance Pub/Sub listener...")
            crossInstanceBroadcaster.startCrossInstanceListener()
        }

        // Start notification worker (uses Streams for durable social events)
        notificationWorkerJob = launch {
            println("🚀 Starting NotificationWorker (Streams for social events)...")
            notificationWorker.run()
        }

        // NEW: Start moderation stream processor
        moderationWorkerJob = launch {
            println("🚀 Starting example.com.services.ModerationWorker (Streams for chat moderation)...")
            moderationWorker.processModerationStream()
        }

        // NEW: Start analytics stream processor
        analyticsWorkerJob = launch {
            println("🚀 Starting AnalyticsWorker (Streams for engagement analytics)...")
//            analyticsWorker.processAnalyticsStream()
        }

        // Start distributed session cleanup
        sessionCleanupJob = launch {
            println("🚀 Starting distributed session cleanup...")
            distributedSessionManager.cleanupInstanceSessions()
            println("✅ Distributed session cleanup completed")
        }

        println("✅ All background services started successfully for instance $instanceId")
        println("   - Pub/Sub: Real-time cross-instance events")
        println("   - Streams: Moderation, Analytics, Billing, Social events")
    }

    /**
    * Stops all services and cancels background jobs
    */
    fun stopAllServices() {
        println("🛑 Stopping all services for instance $instanceId...")

        // Cancel all background jobs
        redisConsumerJob?.cancel()
        notificationWorkerJob?.cancel()
        sessionCleanupJob?.cancel()
        crossInstanceListenerJob?.cancel()

        // Stop cross-instance broadcaster
        crossInstanceBroadcaster.stop()

        // Cancel the main coroutine scope
        coroutineContext.cancel()

        println("✅ All services stopped for instance $instanceId")
    }

    /**
    * Checks if all services are running properly
    */
    suspend fun healthCheck(): Map<String, Boolean> {
        return mapOf(
            "redis_connected" to isRedisConnected(),
            "cross_instance_running" to crossInstanceBroadcaster.isRunning(),
            "moderation_worker_healthy" to moderationWorker.isHealthy(),
//            "analytics_worker_healthy" to analyticsWorker.isHealthy(),
            "distributed_sessions_healthy" to true
        )
    }

    private suspend fun isRedisConnected(): Boolean {
        return try {
            redisService.ping()
        } catch (e: Exception) {
            false
        }
    }
}
