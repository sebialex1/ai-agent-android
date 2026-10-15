package com.sebialex.agent.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sebialex.agent.R
import com.sebialex.agent.domain.agent.SubagentRuns
import com.sebialex.agent.domain.model.Step
import com.sebialex.agent.domain.model.StepKind
import com.sebialex.agent.domain.model.StepStatus
import com.sebialex.agent.domain.model.SubagentRun
import com.sebialex.agent.domain.model.SubagentStatus
import com.sebialex.agent.ui.components.AppIcon
import com.sebialex.agent.ui.components.AppSheet
import com.sebialex.agent.ui.components.QuietIconButton
import com.sebialex.agent.ui.theme.Palette
import com.sebialex.agent.ui.theme.softSurface

/**
 * The subagents of one answer: a quiet status line ("Working · 3 steps completed") and one
 * compact card per subagent (its objective, the newest step, a stop button while it runs).
 * Tapping a card opens its steps in a sheet. Nothing else: the steps never join the answer's
 * own step list.
 */
@Composable
fun SubagentCards(runs: List<SubagentRun>, onOpenStep: (Step) -> Unit, onStop: (String) -> Unit) {
    if (runs.isEmpty()) return
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable { open = (runs.lastOrNull { it.status == SubagentStatus.RUNNING } ?: runs.last()).id }
                .padding(start = 2.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(SubagentRuns.statusLine(runs), style = MaterialTheme.typography.labelMedium, color = Palette.TextTertiary)
            Spacer(Modifier.width(4.dp))
            AppIcon(R.drawable.ic_chevron_right, "Show steps", tint = Palette.TextTertiary, size = 15.dp)
        }
        runs.forEach { run -> SubagentCard(run, onClick = { open = run.id }, onStop = { onStop(run.id) }) }
    }
    val shown = open?.let { id -> runs.firstOrNull { it.id == id } }
    if (shown != null) SubagentSheet(shown, onDismiss = { open = null }, onOpenStep = { open = null; onOpenStep(it) }, onStop = { onStop(shown.id) })
}

@Composable
private fun SubagentCard(run: SubagentRun, onClick: () -> Unit, onStop: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .softSurface(shape, Palette.Surface, elevation = 2.dp)
            .clip(shape)
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(R.drawable.ic_terminal, null, tint = if (run.status == SubagentStatus.FAILED) Palette.Danger else Palette.IconMuted, size = 18.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(run.objective, style = MaterialTheme.typography.bodyMedium, color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                SubagentRuns.detailLine(run),
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (run.status == SubagentStatus.RUNNING) StopSquare(onStop) else Spacer(Modifier.width(8.dp))
    }
}

/** A small square stop button, the same mark as the input bar's stop. */
@Composable
private fun StopSquare(onStop: () -> Unit) {
    QuietIconButton(R.drawable.ic_stop, "Stop subagent", onClick = onStop, size = 36.dp, iconSize = 14.dp, tint = Palette.IconActive)
}

/** One subagent's steps, in order, each opening the usual step detail. */
@Composable
private fun SubagentSheet(run: SubagentRun, onDismiss: () -> Unit, onOpenStep: (Step) -> Unit, onStop: () -> Unit) {
    AppSheet(onDismiss = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(R.drawable.ic_terminal, null, tint = Palette.IconMuted, size = 18.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(run.objective, style = MaterialTheme.typography.titleSmall, color = Palette.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    com.sebialex.agent.ui.settings.modelName(run.model) + " \u00b7 " + SubagentRuns.statusLine(listOf(run)),
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (run.status == SubagentStatus.RUNNING) StopSquare(onStop)
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            val steps = run.steps.filterNot { it.kind == StepKind.REASONING && it.output.isBlank() }
            if (steps.isEmpty()) Text(
                "Starting\u2026",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.TextTertiary,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            )
            steps.forEach { SheetStepRow(it, onClick = { onOpenStep(it) }) }
            if (run.result.isNotBlank() && run.status != SubagentStatus.RUNNING) {
                Text(
                    run.result.trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextSecondary,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = 8.dp, start = 4.dp, end = 4.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Palette.SurfaceHigh)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun SheetStepRow(step: Step, onClick: () -> Unit) {
    val failed = step.status == StepStatus.FAILED || step.status == StepStatus.DENIED
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            AppIcon(
                if (step.kind == StepKind.REASONING) R.drawable.ic_lightbulb else iconFor(step.kind), null,
                tint = if (failed) Palette.Danger else Palette.IconMuted, size = 16.dp,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            SubagentRuns.summary(step),
            style = MaterialTheme.typography.bodyMedium,
            color = if (step.status == StepStatus.RUNNING) Palette.TextTertiary else Palette.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        AppIcon(R.drawable.ic_chevron_right, null, tint = Palette.TextTertiary, size = 14.dp)
    }
}
