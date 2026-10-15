package com.sebialex.agent.data.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.sebialex.agent.MainActivity
import com.sebialex.agent.R

/**
 * Keeps the app process alive while an agent run is in flight and the app is not on screen.
 *
 * Android stops background work aggressively: without a foreground service a run that is
 * waiting for the model or a command can be cut off seconds after the user switches apps. A
 * foreground service (type dataSync: the agent is exchanging data with the model provider and
 * the workspace) is the platform's sanctioned way to say "this work is user-visible, keep it",
 * and it shows a quiet ongoing notification while it lasts. A partial wake lock keeps the CPU
 * awake for the stream with the screen off, released the moment the last run ends.
 *
 * It holds no state of its own: [RunKeeper] starts it when the first run starts and stops it
 * after the last one finishes. Nothing can make an app unkillable, so a run cut short anyway is
 * offered "Continue from where it stopped" on the next launch (see Resume).
 */
class AgentRunService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val count = intent?.getIntExtra(EXTRA_COUNT, 1) ?: 1
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(this, count),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        } catch (e: Exception) {
            // Not allowed right now (started from the background, or the daily limit is used up):
            // the run still goes on as long as Android lets the app live.
            stopSelf()
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "agent:run")
                .apply { setReferenceCounted(false); acquire(WAKE_LOCK_MAX_MS) }
        }
        // If Android kills the process anyway, the runs are gone with it: do not restart empty.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_COUNT = "count"
        const val NOTIFICATION_ID = 4107
        private const val CHANNEL_ID = "agent_runs"
        /** A safety cap; the lock is released as soon as the last run ends. */
        private const val WAKE_LOCK_MAX_MS = 2 * 60 * 60 * 1000L

        fun notification(context: Context, count: Int): Notification {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Agent runs", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Shown while the agent works with the app in the background"
                        setShowBadge(false)
                    },
                )
            }
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_terminal)
                .setContentTitle("Agent is working")
                .setContentText(if (count <= 1) "A conversation keeps running in the background" else "$count conversations keep running in the background")
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }
    }
}
