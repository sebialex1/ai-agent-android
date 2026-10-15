package com.sebialex.agent.data.web

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs blocking network I/O ([block], on the caller's thread, which must be an IO thread) so
 * that cancelling the coroutine really stops it. A blocking socket read does not notice
 * coroutine cancellation by itself: after Stop the model's stream would keep being read (and
 * billed) until the provider finished. Here a cancel calls [close] at once (for a connection:
 * `disconnect()`), the read fails, and the failure is reported as the cancellation it is,
 * never as a network error that could become an answer.
 *
 * [close] is only called on cancellation; normal completion leaves cleanup to [block].
 */
suspend fun <T> cancellableIo(close: () -> Unit, block: () -> T): T = coroutineScope {
    val finished = AtomicBoolean(false)
    val closer = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            if (!finished.get()) runCatching(close)
        }
    }
    try {
        block()
    } catch (e: IOException) {
        // Closed by a cancel: the caller sees the cancellation, not "socket closed".
        ensureActive()
        throw e
    } finally {
        finished.set(true)
        closer.cancel()
    }
}
