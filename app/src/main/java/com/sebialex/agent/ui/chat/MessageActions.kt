package com.sebialex.agent.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import com.sebialex.agent.ui.components.QuietIconButton
import com.sebialex.agent.ui.theme.Palette

/**
 * The actions of one of your own messages, shown when you tap it: a quiet row of icons that
 * unfolds from the bottom edge of the bubble (and folds back into it), sitting between the
 * bubble and whatever comes next, so it never covers another message. No pills, no surface:
 * just Copy, Edit and Delete in the app's icon style. Edit and Delete wait while an answer is
 * running (shown dimmed).
 */
@Composable
fun MessageActionRow(
    visible: Boolean,
    canModify: Boolean,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(tween(ROW_MS), expandFrom = Alignment.Top) + fadeIn(tween(ROW_MS)),
        exit = shrinkVertically(tween(ROW_MS), shrinkTowards = Alignment.Top) + fadeOut(tween(ROW_MS - 60)),
    ) {
        Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            QuietIconButton(R.drawable.ic_copy, "Copy message", onCopy, tint = Palette.IconMuted, size = 36.dp, iconSize = 18.dp)
            QuietIconButton(R.drawable.ic_edit, "Edit message", onEdit, tint = Palette.IconMuted, enabled = canModify, size = 36.dp, iconSize = 18.dp)
            QuietIconButton(R.drawable.ic_trash, "Delete message", onDelete, tint = Palette.IconMuted, enabled = canModify, size = 36.dp, iconSize = 18.dp)
        }
    }
}

/** How long the action row takes to unfold or fold. */
private const val ROW_MS = 180

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
