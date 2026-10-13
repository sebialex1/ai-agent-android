package com.sebialex.agent.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sebialex.agent.R
import com.sebialex.agent.ui.components.QuietIconButton
import com.sebialex.agent.ui.theme.Palette

@Composable
fun ChatTopBar(
    title: String,
    onClose: () -> Unit,
    onNew: () -> Unit,
    onWorkspace: () -> Unit,
    onSettings: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 6.dp),
    ) {
        QuietIconButton(R.drawable.ic_close, "Conversations", onClick = onClose, modifier = Modifier.align(Alignment.CenterStart))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = Palette.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 104.dp),
        )
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
            QuietIconButton(R.drawable.ic_new_chat, "New conversation", onClick = onNew)
            Box {
                // The gear from res/drawable/ic_settings.xml opens Workspace and Settings.
                QuietIconButton(R.drawable.ic_settings, "Settings", onClick = { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Workspace", style = MaterialTheme.typography.bodyMedium, color = Palette.TextPrimary) },
                        leadingIcon = { com.sebialex.agent.ui.components.AppIcon(R.drawable.ic_terminal, null, size = 18.dp) },
                        onClick = { menu = false; onWorkspace() },
                    )
                    DropdownMenuItem(
                        text = { Text("Settings", style = MaterialTheme.typography.bodyMedium, color = Palette.TextPrimary) },
                        leadingIcon = { com.sebialex.agent.ui.components.AppIcon(R.drawable.ic_settings, null, size = 18.dp) },
                        onClick = { menu = false; onSettings() },
                    )
                }
            }
        }
    }
}
