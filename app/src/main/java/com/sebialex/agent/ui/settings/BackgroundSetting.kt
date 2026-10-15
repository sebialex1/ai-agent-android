package com.sebialex.agent.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sebialex.agent.R
import com.sebialex.agent.data.background.BatteryExemption
import com.sebialex.agent.data.background.RunKeeper
import com.sebialex.agent.data.secure.SecureSettings
import com.sebialex.agent.ui.theme.Palette

/** Help shown under the Background group: what is kept alive, and what only the system can change. */
private const val BACKGROUND_HELP =
    "While the agent works, the app keeps itself running with a quiet notification, so a run survives switching apps. " +
        "Unrestricted battery use also keeps it going with the screen off. Commands run inside Termux: on Android 12 and " +
        "later the system may still stop them in the background (\"phantom process\" limit). On Android 14 and later turn " +
        "on Developer options > Disable child process restrictions; on Android 12-13 run once from a computer: " +
        "adb shell device_config put activity_manager max_phantom_processes 2147483647. If a run is cut off anyway, the " +
        "chat offers to continue it."

/** Settings > General > Background: the battery-optimization exemption, with its current state. */
@Composable
internal fun BackgroundSetting() {
    val context = LocalContext.current
    var exempt by remember { mutableStateOf(BatteryExemption.isIgnoring(context)) }
    // Re-read when coming back from the system screen.
    LifecycleResumeEffect(Unit) {
        exempt = BatteryExemption.isIgnoring(context)
        onPauseOrDispose { }
    }
    SettingsGroup("Background", help = BACKGROUND_HELP) {
        NavRow(
            title = "Unrestricted battery use",
            value = if (exempt) "On: runs keep going with the screen off" else "Off: Android may pause a run in the background",
            icon = R.drawable.ic_shield,
            onClick = { BatteryExemption.request(context) },
        )
    }
}

/**
 * Asked once, the first time the app went to the background while the agent was working: keep
 * runs alive (battery exemption, and the notification permission for the "working" notice on
 * Android 13+). Either answer is final; Settings > General > Background can change it later.
 */
@Composable
fun BatteryOffer(keeper: RunKeeper, settings: SecureSettings) {
    val due by keeper.batteryOfferDue.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    if (!due) return
    fun close() {
        settings.setBatteryOfferShown()
        keeper.dismissOffer()
    }
    AlertDialog(
        onDismissRequest = { close() },
        containerColor = Palette.Surface,
        title = { Text("Keep the agent working in the background?", style = MaterialTheme.typography.titleSmall, color = Palette.TextPrimary) },
        text = {
            Text(
                "Android can pause or stop the app soon after you leave it, cutting a run short. Allow unrestricted battery use " +
                    "so runs keep going while you are in another app or the screen is off. You can change this later in " +
                    "Settings > General > Background.",
                style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = {
                close()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) runCatching { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
                BatteryExemption.request(context)
            }) { Text("Allow", color = Palette.TextPrimary) }
        },
        dismissButton = { TextButton(onClick = { close() }) { Text("Not now", color = Palette.TextSecondary) } },
    )
}
