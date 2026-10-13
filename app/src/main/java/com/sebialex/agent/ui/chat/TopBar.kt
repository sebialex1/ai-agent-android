package com.sebialex.agent.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sebialex.agent.R
import com.sebialex.agent.ui.components.QuietIconButton
import com.sebialex.agent.ui.theme.Palette

/**
 * Chat top bar: conversations (X), title, new conversation and the workspace.
 * Chat options (pin, rename, memory, delete) and Settings live only on the conversations
 * screen (the \u22ef next to each title), so the chat has no second control for the same thing.
 */
@Composable
fun ChatTopBar(
    title: String,
    onClose: () -> Unit,
    onNew: () -> Unit,
    onWorkspace: () -> Unit,
) {
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
            QuietIconButton(R.drawable.ic_folder, "Workspace", onClick = onWorkspace)
        }
    }
}
