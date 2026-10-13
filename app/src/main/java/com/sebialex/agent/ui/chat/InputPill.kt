package com.sebialex.agent.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
    onAttach: () -> Unit,
    onMic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canSend = value.text.isNotBlank() && !running
    val fill by animateColorAsState(if (canSend || running) Palette.Accent else Palette.SurfaceHighest, label = "send")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .softSurface(RoundedCornerShape(28.dp), Palette.SurfaceHigh, elevation = 10.dp)
            .heightIn(min = 56.dp)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        QuietIconButton(R.drawable.ic_plus, "Add file", onClick = onAttach, tint = Palette.IconMuted)
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
                        Text("Ask anything", style = MaterialTheme.typography.bodyLarge, color = Palette.TextTertiary)
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
                .clickable(enabled = canSend || running) { if (running) onStop() else onSend() },
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(
                if (running) R.drawable.ic_stop else R.drawable.ic_arrow_up,
                contentDescription = if (running) "Stop" else "Send",
                tint = if (canSend || running) Palette.OnAccent else Palette.IconMuted,
                size = 20.dp,
            )
        }
    }
}
