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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
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
 * One-time commands the user runs inside Termux so this app may use it. Safe to run again.
 * - Creates ~/.termux/termux.properties if needed and adds `allow-external-apps = true` unless an
 *   active line already sets it (with or without spaces around `=`; Java properties accept both).
 *   printf starts with a newline so the line is never glued onto a last line without one, which
 *   would silently turn it into part of a comment.
 * - termux-reload-settings makes the running Termux re-read the file. RunCommandService checks the
 *   copy Termux holds in memory, so without a reload (or a restart) the edit has no effect.
 * - Installs Python (ships pip), then grants Termux shared storage for the mirrored workspace.
 */
private const val TERMUX_SETUP =
    "mkdir -p ~/.termux && touch ~/.termux/termux.properties && " +
        "(grep -Eq '^[[:space:]]*allow-external-apps[[:space:]]*=[[:space:]]*true[[:space:]]*\$' ~/.termux/termux.properties || " +
        "printf '\\nallow-external-apps = true\\n' >> ~/.termux/termux.properties) && termux-reload-settings; " +
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

    fun runCheck() {
        if (checking || !status.ready) return
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
            status = termux.status()
        }
    }
    // The setup step passed when Termux answered, every tool was found and the shared folder is writable.
    val checkOk: Boolean? = check?.let { c -> "termux ok" in c && "missing" !in c && "shared workspace: ok" in c }

    SettingsGroup("Termux sandbox", help = "Commands the agent runs (apt, pkg, pip, python) go through Termux. Each step shows a check when it is done.") {
        // One list, one rhythm: status mark, label, and a plain text action only when something is missing.
        StatusRow(
            label = "Termux installed",
            ok = status.installed,
            action = "Get",
            onAction = { open(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/"))) },
        )
        StatusRow(
            label = "Permission to run commands",
            ok = status.runPermission,
            action = "Allow",
            enabled = status.installed,
            onAction = { askRun.launch(TermuxBridge.PERMISSION_RUN_COMMAND) },
        )
        SetupStep(
            ok = checkOk == true || (check == null && status.externalApps == true),
            failed = checkOk == false || status.externalApps == false,
            checking = checking,
            canCheck = status.ready,
            result = check,
            onCopy = { clipboard.setText(AnnotatedString(TERMUX_SETUP)) },
            onOpenTermux = {
                if (status.externalApps == false) {
                    open(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${TermuxBridge.TERMUX_PACKAGE}")))
                } else openTermux(context)
            },
            onCheck = ::runCheck,
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
    }
}

/**
 * The one step that happens inside Termux: copy the setup command, paste it there, then verify.
 * Compact by default (status mark, label, Copy and Check); the command itself, the hint and the
 * last check output are behind the chevron.
 */
@Composable
private fun SetupStep(
    ok: Boolean,
    failed: Boolean,
    checking: Boolean,
    canCheck: Boolean,
    result: String?,
    onCopy: () -> Unit,
    onOpenTermux: () -> Unit,
    onCheck: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { open = !open }.heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusMark(ok = ok, unknown = !ok && !failed)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Setup command in Termux", style = MaterialTheme.typography.bodyLarge, color = if (ok) Palette.TextSecondary else Palette.TextPrimary)
                Text(
                    when {
                        checking -> "Checking\u2026"
                        ok -> "Verified"
                        failed -> "Not working yet \u00b7 tap for details"
                        else -> "Copy, paste in Termux, then check"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (failed && !checking) Palette.Warning else Palette.TextTertiary,
                )
            }
            QuietIconButton(
                if (copied) R.drawable.ic_check else R.drawable.ic_copy, "Copy Termux setup command",
                onClick = { onCopy(); copied = true }, tint = Palette.IconMuted, size = 38.dp, iconSize = 17.dp,
            )
            QuietIconButton(
                R.drawable.ic_check, "Check that the setup worked", onClick = onCheck, enabled = canCheck && !checking,
                tint = if (ok) Palette.IconActive else Palette.IconMuted, size = 38.dp, iconSize = 18.dp,
            )
            AppIcon(if (open) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right, if (open) "Hide command" else "Show command", tint = Palette.IconMuted, size = 16.dp)
        }
        androidx.compose.animation.AnimatedVisibility(open) {
            Column(Modifier.fillMaxWidth().padding(start = 32.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(TERMUX_SETUP, style = MonoSmall, color = Palette.TextTertiary)
                Text(
                    "Paste it in Termux and press Enter, then tap the check. If it still fails, force stop Termux, reopen it and check again.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.TextTertiary,
                )
                Row { PillButton("Open Termux", onClick = onOpenTermux) }
                result?.let { Text(it, style = MonoSmall, color = Palette.TextSecondary) }
            }
        }
    }
}

@Composable
private fun StatusMark(ok: Boolean, unknown: Boolean) {
    Box(Modifier.width(20.dp), contentAlignment = Alignment.CenterStart) {
        if (unknown && !ok) Text("?", style = MaterialTheme.typography.labelLarge, color = Palette.TextTertiary)
        else AppIcon(
            if (ok) R.drawable.ic_check else R.drawable.ic_close,
            if (ok) "Ready" else "Missing",
            tint = if (ok) Palette.IconActive else Palette.Warning,
            size = 16.dp,
        )
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, action: String, enabled: Boolean = true, unknown: Boolean = false, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(20.dp), contentAlignment = Alignment.CenterStart) {
            if (unknown) Text("?", style = MaterialTheme.typography.labelLarge, color = Palette.TextTertiary)
            else AppIcon(
                if (ok) R.drawable.ic_check else R.drawable.ic_close,
                if (ok) "Ready" else "Missing",
                tint = if (ok) Palette.IconActive else Palette.Warning,
                size = 16.dp,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = if (ok) Palette.TextSecondary else Palette.TextPrimary)
            if (unknown) Text("Not checked yet", style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary)
        }
        if (!ok) PillButton(action, onClick = onAction, enabled = enabled)
    }
}

private fun open(context: android.content.Context, intent: Intent): Boolean = try {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: Exception) {
    false
}

/** Brings Termux to the front so the setup command can be pasted. */
private fun openTermux(context: android.content.Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(TermuxBridge.TERMUX_PACKAGE)
    if (launch != null) open(context, launch)
}

private fun openAppDetails(context: android.content.Context) {
    open(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
}
