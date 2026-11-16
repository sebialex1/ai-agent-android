package com.sebialex.agent.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sebialex.agent.R
import com.sebialex.agent.ui.components.AppIcon
import com.sebialex.agent.ui.components.QuietIconButton
import com.sebialex.agent.ui.theme.Palette
import com.sebialex.agent.ui.theme.softSurface

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
        // New chat and the workspace live in one stretched pill: two segments, one surface.
        Row(
            Modifier
                .align(Alignment.CenterEnd)
                .height(40.dp)
                .softSurface(RoundedCornerShape(50), Palette.SurfaceHigh, elevation = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(width = 48.dp, height = 40.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true, color = Palette.TextPrimary),
                        onClick = onNew,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                AppIcon(R.drawable.ic_new_chat, "New conversation", tint = Palette.IconActive, size = 20.dp)
            }
            Box(
                Modifier
                    .size(width = 48.dp, height = 40.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true, color = Palette.TextPrimary),
                        onClick = onWorkspace,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                AppIcon(R.drawable.ic_folder, "Workspace", tint = Palette.IconActive, size = 20.dp)
            }
        }
    }
}
