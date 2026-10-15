package com.sebialex.agent.data.background

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Starts [AgentRunService] while any agent run is active and stops it when all have finished.
 * The chat calls [update] with the number of running conversations whenever it changes.
 *
 * Stopping waits a moment ([STOP_DELAY_MS]): when a turn ends and the next queued message
 * starts right away, the service simply stays, instead of being stopped and then restarted from
 * the background (which Android refuses).
 */
class RunKeeper(private val app: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var started = false
    private var count = 0
    private val stopLater = Runnable { if (count == 0) stopNow() }

    /** True while at least one run is active. */
    val active: Boolean get() = count > 0

    private val _offerDue = MutableStateFlow(false)

    /**
     * The app went to the background during a run, battery optimization is still on and the
     * offer was never shown: ask once, when the user is back.
     */
    val batteryOfferDue: StateFlow<Boolean> = _offerDue.asStateFlow()

    fun update(running: Int) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { update(running) }; return }
        if (running == count) return
        count = running
        if (running > 0) {
            main.removeCallbacks(stopLater)
            if (!started) {
                started = runCatching {
                    androidx.core.content.ContextCompat.startForegroundService(
                        app, Intent(app, AgentRunService::class.java).putExtra(AgentRunService.EXTRA_COUNT, running),
                    )
                }.isSuccess
            } else {
                // Already in the foreground: only the notification's count changes.
                runCatching {
                    (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                        .notify(AgentRunService.NOTIFICATION_ID, AgentRunService.notification(app, running))
                }
            }
        } else if (started) {
            main.postDelayed(stopLater, STOP_DELAY_MS)
        }
    }

    private fun stopNow() {
        runCatching { app.stopService(Intent(app, AgentRunService::class.java)) }
        started = false
    }

    /** Called when the app leaves the screen. */
    fun onAppBackgrounded(offerShown: Boolean) {
        if (active && !offerShown && !BatteryExemption.isIgnoring(app)) _offerDue.value = true
    }

    fun dismissOffer() { _offerDue.value = false }

    private companion object {
        const val STOP_DELAY_MS = 2_500L
    }
}

/**
 * The battery-optimization exemption ("Unrestricted" battery use). With it Android does not
 * defer the app's network and CPU in Doze or app standby, so a long run survives the screen
 * going off. It is only ever asked for by the user's choice (Settings, or the one-time offer).
 */
object BatteryExemption {
    fun isIgnoring(context: Context): Boolean =
        runCatching { (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName) }
            .getOrDefault(false)

    /**
     * Opens the system prompt that grants the exemption (needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
     * declared in the manifest). Already exempt, or on a phone without that prompt, the battery
     * optimization list opens instead, where it can be changed back.
     */
    fun request(context: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        val first = if (isIgnoring(context)) list else direct
        try {
            context.startActivity(first.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            runCatching { context.startActivity(list.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        } catch (_: SecurityException) {
            runCatching { context.startActivity(list.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
