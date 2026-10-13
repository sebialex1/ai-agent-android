package com.sebialex.agent.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sebialex.agent.R
import com.sebialex.agent.ui.components.AppIcon
import com.sebialex.agent.ui.components.AppSheet
import com.sebialex.agent.ui.components.QuietIconButton
import com.sebialex.agent.ui.components.SheetOption
import com.sebialex.agent.ui.theme.Palette
import com.sebialex.agent.ui.theme.softSurface

/**
 * Long-press menu of one of your own messages, in the app's sheet style: a quoted preview of the
 * message on top, then Copy, Edit and Delete. Edit and Delete wait while an answer is running.
 */
@Composable
fun MessageActionsSheet(
    preview: String,
    canModify: Boolean,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppSheet(onDismiss = onDismiss) {
        // The message itself, as a quiet quote, so it is clear which one the actions apply to.
        Text(
            preview.ifBlank { "Attachment" },
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.TextSecondary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 6.dp, bottom = 10.dp)
                .softSurface(RoundedCornerShape(16.dp), Palette.SurfaceHigh, elevation = 1.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
        SheetOption(R.drawable.ic_copy, "Copy message", onClick = onCopy)
        SheetOption(
            R.drawable.ic_edit, "Edit message", onClick = onEdit,
            subtitle = if (canModify) "Change it and send again; later replies are replaced" else "Available when the answer finishes",
            enabled = canModify,
        )
        SheetOption(
            R.drawable.ic_trash, "Delete message", onClick = onDelete,
            subtitle = if (canModify) "Removes it and its reply" else null,
            danger = canModify, enabled = canModify,
        )
    }
}

/** Thin strip above the input while a sent message is being edited: what is edited, and a cancel. */
@Composable
fun EditingBanner(preview: String, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.Surface)
            .padding(start = 12.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(R.drawable.ic_edit, null, tint = Palette.Accent, size = 15.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            "Editing: ",
            style = MaterialTheme.typography.labelMedium,
            color = Palette.TextSecondary,
        )
        Text(
            preview,
            style = MaterialTheme.typography.labelMedium,
            color = Palette.TextTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        QuietIconButton(R.drawable.ic_close, "Cancel editing", onClick = onCancel, size = 36.dp, iconSize = 16.dp, tint = Palette.IconMuted)
    }
}
