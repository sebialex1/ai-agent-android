package com.sebialex.agent.data.preview

/**
 * When the preview's server runs. It follows the preview window being open and the app being
 * in front, nothing finer: [resume] (the app is resumed with the window open, or the user
 * reloads) makes sure it runs; [background] (the activity stopped: home, app switcher, screen
 * off) starts an idle countdown of [idleMs], which [tick] ends by stopping it, and coming back
 * in time cancels; [close] (the window closed) always stops it, for good.
 *
 * Window focus plays no part: inside a dialog it flips for a popup, the keyboard or a system
 * shade and does not always settle back, which used to stop a server whose window was plainly
 * on screen. Time comes in from outside, so the rules are testable without waiting.
 *
 * [start] binds and returns the port (it has returned only once the socket listens), [stop]
 * releases it, and [alive] says whether the server is still answering: a server that died on
 * its own is started again by the next [resume] instead of being trusted by its old port.
 * Every method is synchronized, since the window calls them from the main thread and from
 * the background thread that binds.
 */
class PreviewLifecycle(
    private val idleMs: Long,
    private val start: (preferredPort: Int) -> Int,
    private val stop: () -> Unit,
    private val alive: () -> Boolean = { true },
) {
    /** The port while the server runs, else null. */
    @get:Synchronized
    var port: Int? = null
        private set

    /** The last port used, so a restart keeps the page's address when it can. */
    private var lastPort = 0
    private var idleSince: Long? = null
    private var closed = false

    /**
     * The app is in front with the window open (or the user asked to reload): the countdown is
     * off and the server runs, started now if it was not (or no longer is). Returns the port,
     * or null once closed. A failed start throws and leaves it stopped.
     */
    @Synchronized
    fun resume(): Int? {
        if (closed) return null
        idleSince = null
        port?.let { p -> if (alive()) return p }
        if (port != null) halt()
        val p = start(lastPort)
        port = p
        lastPort = p
        return p
    }

    /** The activity stopped with the window open: the idle countdown starts (once). */
    @Synchronized
    fun background(now: Long) {
        if (port != null && idleSince == null) idleSince = now
    }

    /** Stops the server once the app has been away for [idleMs]. True when it stopped now. */
    @Synchronized
    fun tick(now: Long): Boolean {
        val since = idleSince ?: return false
        if (port == null || now - since < idleMs) return false
        halt()
        return true
    }

    /** How long until [tick] would stop the server; null when no countdown runs. */
    @Synchronized
    fun remaining(now: Long): Long? = idleSince?.takeIf { port != null }?.let { (idleMs - (now - it)).coerceAtLeast(0) }

    /** The window closed: always stops, and nothing starts it again. */
    @Synchronized
    fun close() {
        closed = true
        halt()
    }

    private fun halt() {
        idleSince = null
        if (port != null) {
            port = null
            stop()
        }
    }

    companion object {
        /** The server keeps running this long after the app leaves the screen. */
        const val IDLE_MS = 60_000L
    }
}
