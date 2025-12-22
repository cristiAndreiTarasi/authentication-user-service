package example.com.services.gifts

import example.com.config.AppJson
import example.com.config.awaitFuture
import example.com.services.redis.RedisStreams
import example.com.services.redis.ShardedRedisService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.sql.DataSource

class OutboxDispatcher(
    private val dataSource: DataSource,
    private val shardedRedisService: ShardedRedisService,
    private val pollIntervalMs: Long = 500L,
    private val batchSize: Int = 50
) {
    private val json: Json = AppJson

    suspend fun start() {
        println("▶️ OutboxDispatcher starting")
        while (true) {
            try {
                val rows = fetchUnpublished(batchSize)
                if (rows.isEmpty()) {
                    delay(pollIntervalMs)
                    continue
                }

                for (row in rows) {
                    try {
                        publishRow(row)
                    } catch (e: Exception) {
                        println("OutboxDispatcher: failed to publish outbox ${row.id}: ${e.message}")
                        markOutboxError(row.id, e.message ?: "unknown")
                    }
                }
            } catch (e: Exception) {
                println("OutboxDispatcher loop error: ${e.message}")
                delay(1000)
            }
        }
    }

    private data class OutboxRow(val id: Long, val streamName: String, val eventType: String, val payload: String)

    private suspend fun fetchUnpublished(limit: Int): List<OutboxRow> = withContext(Dispatchers.IO) {
        val conn = dataSource.connection
        try {
            val ps = conn.prepareStatement("SELECT id, stream_name, event_type, payload FROM outbox WHERE published_at IS NULL ORDER BY created_at ASC LIMIT ?")
            ps.setInt(1, limit)
            val rs = ps.executeQuery()
            val out = mutableListOf<OutboxRow>()
            while (rs.next()) {
                out.add(OutboxRow(rs.getLong("id"), rs.getString("stream_name"), rs.getString("event_type"), rs.getString("payload")))
            }
            rs.close()
            ps.close()
            out
        } finally {
            try { conn.close() } catch (_: Exception) {}
        }
    }

    private suspend fun publishRow(row: OutboxRow) {
        // Map stream_name to RedisService
        val targetRedis = when (row.streamName) {
            RedisStreams.BILLING_EVENTS -> shardedRedisService.billingRedis
            RedisStreams.SOCIAL_EVENTS -> shardedRedisService.socialRedis
            RedisStreams.ANALYTICS_EVENTS -> shardedRedisService.analyticsRedis
            else -> shardedRedisService.analyticsRedis
        }

        // publish to the Redis Stream key = row.streamName (match your RedisStreams constants)
        val map = mapOf("event" to row.payload)
        targetRedis.producerCommands.xadd(row.streamName, map).awaitFuture()

        markOutboxPublished(row.id)
    }

    private fun markOutboxPublished(id: Long) {
        // small DB update on IO dispatcher (not blocking main loop)
        kotlinx.coroutines.runBlocking {
            withContext(Dispatchers.IO) {
                val conn = dataSource.connection
                try {
                    val stmt = conn.prepareStatement("UPDATE outbox SET published_at = now() WHERE id = ?")
                    stmt.setLong(1, id)
                    stmt.executeUpdate()
                    stmt.close()
                } finally {
                    try { conn.close() } catch (_: Exception) {}
                }
            }
        }
    }

    private fun markOutboxError(id: Long, error: String) {
        kotlinx.coroutines.runBlocking {
            withContext(Dispatchers.IO) {
                val conn = dataSource.connection
                try {
                    val stmt = conn.prepareStatement("UPDATE outbox SET retries = retries + 1, last_error = ? WHERE id = ?")
                    stmt.setString(1, error)
                    stmt.setLong(2, id)
                    stmt.executeUpdate()
                    stmt.close()
                } finally {
                    try { conn.close() } catch (_: Exception) {}
                }
            }
        }
    }
}