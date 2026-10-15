package com.sebialex.agent.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.sebialex.agent.R
import com.sebialex.agent.ui.components.AppIcon
import com.sebialex.agent.ui.components.QuietIconButton
import com.sebialex.agent.ui.theme.Palette
import com.sebialex.agent.ui.theme.softSurface

@Composable
fun InputPill(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    running: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: (() -> Unit)?,
    onMic: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Ask anything",
    /** Imports are waiting above the input, so an empty message can still be sent. */
    hasAttachments: Boolean = false,
    /**
     * An answer is still typing out (no run any more): the button stays a stop button, and
     * pressing it shows the whole answer at once.
     */
    revealing: Boolean = false,
    /** The agent asked a question: what is typed here is the answer. */
    answering: Boolean = false,
) {
    // The input is never blocked: while an answer runs, a typed message is queued (one button:
    // the arrow queues it when there is something to send, otherwise it is the stop button).
    val hasContent = value.text.isNotBlank() || hasAttachments
    val canSend = hasContent
    val stoppable = running || revealing
    val showStop = stoppable && !hasContent
    // Snaps (no fade) when the state changes, so after Stop the button is a send button that very frame.
    val fill by animateColorAsState(
        if (canSend || stoppable) Palette.Accent else Palette.SurfaceHighest,
        animationSpec = androidx.compose.animation.core.snap(),
        label = "send",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .softSurface(RoundedCornerShape(28.dp), Palette.SurfaceHigh, elevation = 10.dp)
            .heightIn(min = 56.dp)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        if (onAttach != null) {
            QuietIconButton(R.drawable.ic_plus, "Add photos, files and options", onClick = onAttach, tint = Palette.IconMuted)
        } else {
            Spacer(Modifier.width(10.dp))
        }
        Box(
            Modifier
                .weight(1f)
                .heightIn(min = 44.dp)
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Palette.TextPrimary),
                cursorBrush = SolidColor(Palette.TextPrimary),
                maxLines = 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                decorationBox = { inner ->
                    if (value.text.isEmpty()) {
                        Text(if (answering) "Type your answer" else if (running) "Queue a message" else placeholder, style = MaterialTheme.typography.bodyLarge, color = Palette.TextTertiary)
                    }
                    inner()
                },
            )
        }
        QuietIconButton(R.drawable.ic_mic, "Voice input", onClick = onMic, tint = Palette.IconMuted)
        Box(
            Modifier
                .padding(start = 2.dp, end = 2.dp, top = 4.dp, bottom = 4.dp)
                .size(36.dp)
                .clip(CircleShape)
                .background(fill)
                .clickable(enabled = canSend || stoppable) { if (showStop) onStop() else onSend() },
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(
                if (showStop) R.drawable.ic_stop else R.drawable.ic_arrow_up,
                contentDescription = when {
                    showStop -> "Stop"
                    answering -> "Send answer"
                    running -> "Queue message"
                    else -> "Send"
                },
                tint = if (canSend || stoppable) Palette.OnAccent else Palette.IconMuted,
                size = 20.dp,
            )
        }
    }
}
