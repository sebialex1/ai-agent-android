package com.sebialex.agent.data.preview

/**
 * When the preview's server runs: from the moment the preview window is open and focused,
 * through short trips away (a notification, the app switcher), until it has been out of focus
 * for [idleMs]; closing the window always stops it, and focusing it again starts it again.
 * Time comes in from outside ([tick] with the current clock), so the rules are testable
 * without waiting. [start] returns the port; [stop] releases it.
 */
class PreviewLifecycle(
    private val idleMs: Long,
    private val start: (preferredPort: Int) -> Int,
    private val stop: () -> Unit,
) {
    /** The port while the server runs, else null. */
    var port: Int? = null
        private set

    /** The last port used, so a restart keeps the page's address when it can. */
    private var lastPort = 0
    private var blurredAt: Long? = null
    private var closed = false

    /** The window is visible and focused. Returns true when the server was (re)started just now. */
    fun focus(): Boolean {
        if (closed) return false
        blurredAt = null
        if (port != null) return false
        val p = start(lastPort)
        port = p
        lastPort = p
        return true
    }

    /** The window lost focus (app in the background, screen off, another window on top). */
    fun blur(now: Long) {
        if (port != null && blurredAt == null) blurredAt = now
    }

    /** Stops the server once it has been out of focus for [idleMs]. True when it stopped now. */
    fun tick(now: Long): Boolean {
        val since = blurredAt ?: return false
        if (port == null || now - since < idleMs) return false
        halt()
        return true
    }

    /** How long until [tick] would stop the server; null when nothing is pending. */
    fun remaining(now: Long): Long? = blurredAt?.takeIf { port != null }?.let { (idleMs - (now - it)).coerceAtLeast(0) }

    /** The window closed: always stops, and the lifecycle is finished. */
    fun close() {
        closed = true
        halt()
    }

    private fun halt() {
        blurredAt = null
        if (port != null) {
            port = null
            stop()
        }
    }

    companion object {
        /** The server keeps running this long after the window loses focus. */
        const val IDLE_MS = 60_000L
    }
}
