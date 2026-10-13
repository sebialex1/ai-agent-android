package com.sebialex.agent.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sebialex.agent.R
import com.sebialex.agent.data.shell.SandboxShell
import com.sebialex.agent.data.shell.TermuxBridge
import com.sebialex.agent.data.shell.TermuxException
import com.sebialex.agent.ui.components.AppIcon
import com.sebialex.agent.ui.components.QuietIconButton
import com.sebialex.agent.ui.theme.MonoSmall
import com.sebialex.agent.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One-time commands the user runs inside Termux so this app may use it. Idempotent: enables allow-external-apps once (and reloads Termux's settings so it applies
 * without a restart), installs Python (which ships pip), then grants Termux shared storage so
 * it can reach the mirrored workspace under /storage/emulated/0/AgentSandbox.
 */
private const val TERMUX_SETUP =
    "mkdir -p ~/.termux && (grep -qx 'allow-external-apps = true' ~/.termux/termux.properties 2>/dev/null || " +
        "echo 'allow-external-apps = true' >> ~/.termux/termux.properties) && termux-reload-settings; " +
        "pkg install -y python; termux-setup-storage"

/**
 * Status of the Termux environment that runs apt, pkg, pip and python, with one tap per
 * missing piece. Refreshed whenever the screen resumes (after returning from system settings).
 */
@Composable
fun SandboxSetting(termux: TermuxBridge) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(termux.status()) }
    var check by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) status = termux.status() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val askRun = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        status = termux.status()
        if (!granted) openAppDetails(context)
    }
    val askLegacyStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { status = termux.status() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Sandbox", style = MaterialTheme.typography.labelLarge, color = Palette.TextSecondary)
        StatusRow(
            label = "Termux",
            ok = status.installed,
            action = "Get Termux",
            onAction = { open(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/"))) },
        )
        StatusRow(
            label = "Run commands in Termux",
            ok = status.runPermission,
            action = "Allow",
            enabled = status.installed,
            onAction = { askRun.launch(TermuxBridge.PERMISSION_RUN_COMMAND) },
        )
        StatusRow(
            label = "All files access",
            ok = status.storage,
            action = "Allow",
            onAction = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val ok = open(
                        context,
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")),
                    )
                    if (!ok) open(context, Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } else {
                    askLegacyStorage.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            },
        )
        // The Termux side cannot be configured from here; show the exact one-time command.
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.SurfaceHigh).padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(TERMUX_SETUP, style = MonoSmall, color = Palette.TextSecondary, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
            QuietIconButton(R.drawable.ic_copy, "Copy Termux setup command", onClick = {
                clipboard.setText(AnnotatedString(TERMUX_SETUP))
            }, tint = Palette.IconMuted, size = 40.dp, iconSize = 18.dp)
        }
        Text(
            "Run this once in Termux, then tap Check.",
            style = MaterialTheme.typography.labelSmall,
            color = Palette.TextTertiary,
        )
        Text(
            if (checking) "Checking\u2026" else "Check",
            style = MaterialTheme.typography.labelLarge,
            color = if (status.ready && !checking) Palette.TextPrimary else Palette.TextTertiary,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Palette.SurfaceHigh)
                .clickable(enabled = status.ready && !checking) {
                    checking = true
                    scope.launch {
                        check = withContext(Dispatchers.IO) {
                            try {
                                // Also proves Termux can write the shared workspace root, which is
                                // what every mirrored command needs (termux-setup-storage).
                                val shared = termux.sharedRoot.apply { mkdirs() }.absolutePath
                                val q = SandboxShell.shellQuote(shared)
                                val r = termux.run(
                                    "echo \"termux ok\"; for p in bash apt dpkg pkg python pip; do printf '%s: ' \"\$p\"; command -v \"\$p\" || echo missing; done; " +
                                        "if touch $q/.agent-check 2>/dev/null && rm -f $q/.agent-check; then echo 'shared workspace: ok'; " +
                                        "else echo 'shared workspace: not writable from Termux (run termux-setup-storage in Termux)'; fi",
                                    workdir = "~/",
                                    timeoutSeconds = SandboxShell.TIMEOUT_SECONDS,
                                    label = "agent environment check",
                                )
                                val out = (r.stdout + r.stderr).trim()
                                if (r.err != -1 && r.errmsg.isNotBlank()) "termux error ${r.err}: ${r.errmsg.trim()}" else out.ifEmpty { "exit ${r.exitCode}" }
                            } catch (e: TermuxException) {
                                e.message ?: "Termux did not answer"
                            }
                        }
                        checking = false
                    }
                }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
        check?.let { Text(it, style = MonoSmall, color = Palette.TextSecondary) }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, action: String, enabled: Boolean = true, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(
            if (ok) R.drawable.ic_check else R.drawable.ic_close,
            if (ok) "Ready" else "Missing",
            tint = if (ok) Palette.IconMuted else Palette.TextTertiary,
            size = 16.dp,
        )
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary, modifier = Modifier.weight(1f))
        if (!ok) Text(
            action,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) Palette.TextPrimary else Palette.TextTertiary,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Palette.SurfaceHigh)
                .clickable(enabled = enabled, onClick = onAction)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

private fun open(context: android.content.Context, intent: Intent): Boolean = try {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: Exception) {
    false
}

private fun openAppDetails(context: android.content.Context) {
    open(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
}
