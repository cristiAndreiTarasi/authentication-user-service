package example.com

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
 * Provides dependency injection and coordinated startup/shutdown.
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

    // Background jobs
    private var redisConsumerJob: Job? = null
    private var notificationWorkerJob: Job? = null
    private var sessionCleanupJob: Job? = null
    private var crossInstanceListenerJob: Job? = null

    /**
    * Starts all background workers and services
    */
    fun startAllServices() {
        println("🚀 Starting all background services for instance $instanceId...")

        // Start Redis event consumer
        redisConsumerJob = launch {
            println("🚀 Starting Redis consumeEvents worker...")
            redisService.consumeEvents("live-group", crossInstanceBroadcaster)
        }

        // Start notification worker
        notificationWorkerJob = launch {
            println("🚀 Starting NotificationWorker...")
            notificationWorker.run()
        }

        // Start distributed session cleanup
        sessionCleanupJob = launch {
            println("🚀 Starting distributed session cleanup...")
            distributedSessionManager.cleanupInstanceSessions()
            println("✅ Distributed session cleanup completed")
        }

        // Start cross-instance message listener
        crossInstanceListenerJob = launch {
            println("🚀 Starting cross-instance broadcaster...")
            crossInstanceBroadcaster.startCrossInstanceListener()
            println("✅ Cross-instance broadcaster started")
        }

        println("✅ All background services started successfully for instance $instanceId")
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
            "distributed_sessions_healthy" to true // Add actual health checks as needed
        )
    }

    private suspend fun isRedisConnected(): Boolean {
        return try {
            redisService.producerCommands.ping() == "PONG"
        } catch (e: Exception) {
            false
        }
    }
}
