package com.sebialex.agent.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sebialex.agent.R
import com.sebialex.agent.domain.model.AskRecord
import com.sebialex.agent.ui.components.AppIcon
import com.sebialex.agent.ui.theme.Palette
import com.sebialex.agent.ui.theme.softSurface

/**
 * A question the agent asked (ask_user). While it waits, the choices are buttons and the input
 * bar below answers it too; once answered the card keeps the reply, quietly. A question left
 * without a reply (the turn was stopped) says so.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuestionCard(record: AskRecord, waiting: Boolean, onAnswer: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .softSurface(RoundedCornerShape(20.dp), Palette.Surface, elevation = if (waiting) 4.dp else 1.dp)
            .padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            AppIcon(R.drawable.ic_info, null, tint = if (waiting) Palette.Accent else Palette.IconMuted, size = 17.dp, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.width(10.dp))
            Text(record.question, style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary)
        }
        val answer = record.answer
        when {
            answer != null -> Row(Modifier.padding(start = 27.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(R.drawable.ic_check, null, tint = Palette.TextTertiary, size = 14.dp)
                Spacer(Modifier.width(6.dp))
                Text(answer, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
            }
            waiting -> {
                if (record.choices.isNotEmpty()) FlowRow(
                    Modifier.padding(start = 27.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    record.choices.forEach { c -> PillButton(c, primary = false) { onAnswer(c) } }
                }
                Text(
                    if (record.choices.isEmpty()) "Type your answer below" else "Or type your own answer below",
                    style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary,
                    modifier = Modifier.padding(start = 27.dp),
                )
            }
            else -> Text(
                "Not answered", style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary,
                modifier = Modifier.padding(start = 27.dp),
            )
        }
    }
}
