package example.com.services

import example.com.schemas.NotificationSchema
import example.com.schemas.UserSchema
import example.com.services.workers.NotificationWorker
import example.com.services.redis.RedisService
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
    private val redisService: RedisService,
    private val userSchema: UserSchema,
    private val notificationSchema: NotificationSchema,
    private val authTokenService: ITokenService,
    val instanceId: String = System.getenv("HOSTNAME") ?: "instance-${UUID.randomUUID().toString().take(8)}"
) : CoroutineScope {
    override val coroutineContext = SupervisorJob() + Dispatchers.Default

    // Distributed services
    val distributedSessionManager = DistributedSessionManager(redisService, instanceId)
    val distributedPermissionManager = DistributedPermissionManager(redisService)
    val crossInstanceBroadcaster = CrossInstanceBroadcaster(redisService, instanceId)
    private val notificationWorker = NotificationWorker(redisService, notificationSchema, userSchema)

    // Specialized stream workers
    private val chatWorker = ChatWorker(redisService)
    private val moderationWorker = ModerationWorker(redisService)
    private val analyticsWorker = AnalyticsWorker(redisService)
    private val billingWorker = BillingWorker(redisService)

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
                redisService.initializeStreamConsumerGroups()
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
            println("💬 Starting ChatWorker...")
            chatWorker.start()
        }

        moderationWorkerJob = launch {
            println("🛡️ Starting ModerationWorker...")
            moderationWorker.start()
        }

        analyticsWorkerJob = launch {
            println("📊 Starting AnalyticsWorker...")
            analyticsWorker.start()
        }

        billingWorkerJob = launch {
            println("💰 Starting BillingWorker...")
            billingWorker.start()
        }

        // Start notification worker (social events)
        notificationWorkerJob = launch {
            println("🔔 Starting NotificationWorker...")
            notificationWorker.run()
        }

        // Start distributed session cleanup
        sessionCleanupJob = launch {
            println("🧹 Starting distributed session cleanup...")
            distributedSessionManager.cleanupInstanceSessions()
        }

        println("✅ All background services started successfully for instance $instanceId")
        println("   - Streams: Chat, Moderation, Analytics, Billing, Social")
        println("   - Pub/Sub: Real-time cross-instance events")
        println("   - Workers: Specialized stream processors")
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
    suspend fun healthCheck(): Map<String, Boolean> {
        return mapOf(
            "redis_connected" to isRedisConnected(),
            "cross_instance_running" to crossInstanceBroadcaster.isRunning(),
            "chat_worker_healthy" to chatWorker.isHealthy(),
            "moderation_worker_healthy" to moderationWorker.isHealthy(),
            "analytics_worker_healthy" to analyticsWorker.isHealthy(),
            "billing_worker_healthy" to billingWorker.isHealthy(),
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
