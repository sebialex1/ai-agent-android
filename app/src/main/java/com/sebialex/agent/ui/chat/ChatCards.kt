package com.sebialex.agent.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.sebialex.agent.R
import com.sebialex.agent.domain.agent.Plans
import com.sebialex.agent.domain.model.AskRecord
import com.sebialex.agent.domain.model.Plan
import com.sebialex.agent.domain.model.PlanStatus
import com.sebialex.agent.ui.components.AppIcon
import com.sebialex.agent.ui.theme.Palette
import com.sebialex.agent.ui.theme.softSurface

/**
 * The plan the agent set for this answer (set_plan): a title with "2 of 5", a thin progress
 * line that fills as steps finish, and the steps with checkbox-like states: done (ticked,
 * quiet), active (a softly pulsing ring, bright text), pending (empty box), skipped (struck).
 * Each set_plan call replaces the plan, so the card moves along with the work.
 */
@Composable
fun PlanCard(plan: Plan, live: Boolean) {
    val (done, total) = Plans.progress(plan)
    val progress by animateFloatAsState(if (total == 0) 0f else done / total.toFloat(), animationSpec = tween(320), label = "plan")
    Column(
        Modifier
            .fillMaxWidth()
            .softSurface(RoundedCornerShape(18.dp), Palette.Surface, elevation = 2.dp)
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(plan.title, style = MaterialTheme.typography.titleSmall, color = Palette.TextPrimary, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            Text("$done of $total", style = MaterialTheme.typography.labelMedium, color = Palette.TextTertiary)
        }
        Box(
            Modifier
                .padding(top = 9.dp, bottom = 6.dp)
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Palette.SurfaceHighest),
        ) {
            Box(Modifier.fillMaxWidth(progress).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Palette.Accent))
        }
        plan.steps.forEach { step ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                    when (step.status) {
                        PlanStatus.DONE -> AppIcon(R.drawable.ic_checkbox_checked, "Done", tint = Palette.TextSecondary, size = 17.dp)
                        PlanStatus.ACTIVE -> ActiveRing(live)
                        PlanStatus.SKIPPED -> AppIcon(R.drawable.ic_checkbox, "Skipped", tint = Palette.TextTertiary, size = 17.dp, modifier = Modifier.alpha(0.6f))
                        PlanStatus.PENDING -> AppIcon(R.drawable.ic_checkbox, "To do", tint = Palette.IconMuted, size = 17.dp)
                    }
                }
                Spacer(Modifier.width(8.dp))
                val color by animateColorAsState(
                    when (step.status) {
                        PlanStatus.ACTIVE -> Palette.TextPrimary
                        PlanStatus.PENDING -> Palette.TextSecondary
                        else -> Palette.TextTertiary
                    },
                    label = "planStep",
                )
                Text(
                    step.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = color,
                    fontWeight = if (step.status == PlanStatus.ACTIVE) FontWeight.Medium else null,
                    textDecoration = if (step.status == PlanStatus.SKIPPED) TextDecoration.LineThrough else null,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
    }
}

/** The step in progress: a ring that breathes while the turn runs, still once it ended. */
@Composable
private fun ActiveRing(live: Boolean) {
    val pulse = rememberInfiniteTransition(label = "active")
    val a by pulse.animateFloat(0.45f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "ring")
    Box(
        Modifier
            .size(14.dp)
            .alpha(if (live) a else 0.8f)
            .clip(CircleShape)
            .background(Palette.Accent.copy(alpha = 0.25f))
            .padding(3.dp)
            .clip(CircleShape)
            .background(Palette.Accent),
    )
}

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
