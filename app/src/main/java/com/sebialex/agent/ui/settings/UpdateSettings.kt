package com.sebialex.agent.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sebialex.agent.data.update.UpdateFlow
import com.sebialex.agent.ui.theme.Palette

/**
 * Settings > Updates: one compact row (status, detail, a progress bar while downloading, at most
 * one action). All state lives in [UpdateFlow] (app-wide), so recomposing, rotating or reopening
 * this page never repeats a check, a download or an installer launch; the page only shows the
 * state and forwards taps. Text and action per state come from [UpdateRows].
 */
@Composable
internal fun UpdateSettings(updates: UpdateFlow) {
    val context = LocalContext.current
    val state by updates.state.collectAsStateWithLifecycle()
    // One automatic check per app run; UpdateFlow ignores repeats.
    LaunchedEffect(Unit) { updates.checkOnce() }
    // Back from "Install unknown apps": with the permission granted the installer opens once.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { updates.onResume() }
    val row = UpdateRows.row(state, updates.installed)

    fun open(url: String) {
        runCatching {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    SettingsGroup("Updates") {
        Column(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(row.status, style = MaterialTheme.typography.bodyLarge, color = if (row.warning) Palette.Warning else Palette.TextPrimary)
                    Text(row.detail, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary)
                }
                row.action?.let { action ->
                    Spacer(Modifier.width(8.dp))
                    PillButton(
                        action.label,
                        onClick = {
                            when (action) {
                                UpdateAction.CHECK -> updates.check()
                                UpdateAction.RETRY -> updates.retry()
                                UpdateAction.ALLOW_INSTALLS -> updates.allowInstalls()
                                UpdateAction.INSTALL -> updates.install()
                                UpdateAction.RELEASE_NOTES -> row.url?.let(::open)
                            }
                        },
                        primary = action == UpdateAction.INSTALL || action == UpdateAction.ALLOW_INSTALLS,
                    )
                }
            }
            if (row.progress != null || row.indeterminate) {
                val mod = Modifier.fillMaxWidth().padding(top = 8.dp).height(3.dp).clip(RoundedCornerShape(2.dp))
                val p = row.progress
                if (p != null) {
                    LinearProgressIndicator(progress = { p }, modifier = mod, color = Palette.Accent, trackColor = Palette.SurfaceHigh)
                } else {
                    LinearProgressIndicator(modifier = mod, color = Palette.Accent, trackColor = Palette.SurfaceHigh)
                }
            }
        }
    }
}
