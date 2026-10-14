package com.sebialex.agent.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sebialex.agent.data.update.InstalledInfo
import com.sebialex.agent.data.update.UpdateChecker
import com.sebialex.agent.data.update.UpdateResult
import com.sebialex.agent.ui.theme.Palette
import kotlinx.coroutines.launch

/**
 * Settings > Updates: compares this install with the newest build on GitHub Releases. Checking
 * is read-only; "Download" hands the APK link to the system, so Android's own installer asks the
 * user to confirm (and to allow installs from that app, if needed). Nothing installs silently.
 */
@Composable
internal fun UpdateSettings(checker: UpdateChecker = remember { UpdateChecker() }) {
    val context = LocalContext.current
    val installed = remember { InstalledInfo.of(context) }
    var result by remember { mutableStateOf<UpdateResult?>(null) }
    var checking by remember { mutableStateOf(false) }
    var autoChecked by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun check() {
        if (checking) return
        checking = true
        scope.launch {
            result = checker.check(installed)
            checking = false
        }
    }
    // One automatic check when the page opens; later ones only on request.
    LaunchedEffect(Unit) { if (!autoChecked) { autoChecked = true; check() } }
    fun open(url: String) {
        runCatching {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    SettingsGroup("This install") {
        InfoRow("Version", "${installed.versionName} \u00b7 build ${installed.versionCode}")
    }
    SettingsGroup("Latest release") {
        val r = result
        when {
            checking -> StatusText("Checking GitHub Releases\u2026")
            r is UpdateResult.Available -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoRow("Update available", "${r.release.name} \u00b7 build ${r.release.versionCode}" + (r.release.apkSize?.let { " \u00b7 ${it / (1024 * 1024)} MB" } ?: ""))
                if (r.keyChanged) StatusText(
                    "Signed with a different key than this install, so Android will refuse it as an update. " +
                        "Back up first (Backup & restore), uninstall, install it, then restore. Needed once.",
                    warning = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    r.release.apkUrl?.let { url -> PillButton("Download", onClick = { open(url) }, primary = true) }
                    PillButton("Release notes", onClick = { open(r.release.pageUrl) })
                }
            }
            r is UpdateResult.UpToDate -> StatusText(
                "You have the latest build" + (r.latest?.let { " (${it.name})" } ?: "") + ".",
            )
            r is UpdateResult.Offline -> StatusText("No connection. Check the network and try again.", warning = true)
            r is UpdateResult.Failed -> StatusText("Could not check: ${r.message}.", warning = true)
            else -> StatusText("Not checked yet.")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(if (checking) "Checking\u2026" else "Check now", onClick = { check() }, enabled = !checking)
            PillButton("All releases", onClick = { open(UpdateChecker.RELEASES_PAGE) })
        }
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary)
        Text(value, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary)
    }
}

@Composable
private fun StatusText(text: String, warning: Boolean = false) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = if (warning) Palette.Warning else Palette.TextSecondary)
}
