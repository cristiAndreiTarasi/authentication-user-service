package example.com.config

import io.lettuce.core.RedisFuture
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Coroutine-friendly await helper for Lettuce RedisFuture.
 * Replaces duplicate implementations found in multiple classes.
 *
 * Usage:
 *   val result = producerCommands.publish("ch", msg).await()
 */
suspend fun <T> RedisFuture<T>.awaitFuture(): T = suspendCancellableCoroutine { cont ->
    try {
        // If already completed, resume synchronously
        if (isDone) {
            try {
                cont.resume(get())
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
            return@suspendCancellableCoroutine
        }

        // Attach Lettuce handler
        handle { res, err ->
            if (err != null) {
                cont.resumeWithException(err)
            } else {
                cont.resume(res)
            }
        }

        // If coroutine is cancelled, try cancelling the RedisFuture too
        cont.invokeOnCancellation {
            try { cancel(true) } catch (_: Throwable) { /* ignore */ }
        }
    } catch (e: Throwable) {
        cont.resumeWithException(e)
    }
}
