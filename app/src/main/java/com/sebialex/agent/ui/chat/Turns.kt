package com.sebialex.agent.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import com.sebialex.agent.R
import com.sebialex.agent.domain.agent.ApprovalRequest
import com.sebialex.agent.domain.model.FileAttachment
import com.sebialex.agent.domain.model.SourceRef
import com.sebialex.agent.domain.model.Step
import com.sebialex.agent.domain.model.StepKind
import com.sebialex.agent.domain.model.StepStatus
import com.sebialex.agent.domain.model.Turn
import com.sebialex.agent.ui.components.AppIcon
import com.sebialex.agent.ui.components.Favicon
import com.sebialex.agent.ui.components.FadingText
import com.sebialex.agent.ui.markdown.MarkdownContent
import com.sebialex.agent.ui.theme.MonoSmall
import com.sebialex.agent.ui.theme.Palette
import com.sebialex.agent.ui.theme.softSurface

@Composable
fun UserMessage(turn: Turn) {
    Box(Modifier.fillMaxWidth().padding(start = 56.dp), contentAlignment = Alignment.CenterEnd) {
        Text(
            turn.text,
            style = MaterialTheme.typography.bodyLarge,
            color = Palette.TextPrimary,
            modifier = Modifier
                .softSurface(RoundedCornerShape(22.dp), Palette.SurfaceHigh, elevation = 2.dp)
                .padding(horizontal = 16.dp, vertical = 11.dp),
        )
    }
}

/**
 * Fixed visual order: execution row, answer, file attachments, website sources.
 */
@Composable
fun AssistantTurn(
    turn: Turn,
    expanded: Boolean,
    pendingApproval: ApprovalRequest?,
    onToggle: () -> Unit,
    onOpenStep: (Step) -> Unit,
    onOpenFile: (FileAttachment) -> Unit,
    onOpenSource: (SourceRef) -> Unit,
    onMoreSources: (List<SourceRef>) -> Unit,
    onApproval: (Boolean) -> Unit,
    reveal: Boolean = false,
    onRevealed: () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (turn.steps.isNotEmpty()) {
            StepsGroup(turn.steps, expanded, onToggle, onOpenStep)
        }
        if (pendingApproval != null && turn.steps.any { it.id == pendingApproval.stepId }) {
            ApprovalCard(pendingApproval, onApproval)
        }
        if (turn.text.isNotBlank()) {
            if (reveal) TypewriterMarkdown(turn.text, onRevealed) else MarkdownContent(turn.text, compact = true)
        }
        // Files and sources follow the answer once it has been revealed.
        if (turn.attachments.isNotEmpty() || turn.sources.isNotEmpty()) AnimatedVisibility(visible = !reveal, enter = fadeIn(tween(220)), exit = fadeOut(tween(1))) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (turn.attachments.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        turn.attachments.forEach { FilePill(it, onClick = { onOpenFile(it) }) }
                    }
                }
                if (turn.sources.isNotEmpty()) {
                    SourceCluster(turn.sources, onOpenSource, onMoreSources)
                }
            }
        }
    }
}

/**
 * Reveals a freshly received answer once. The speed is time-based (independent of refresh rate)
 * and accelerates: each frame adds a base amount plus a share of what is already visible plus a
 * share of the total length, so short answers type out quickly and long answers do not drag
 * (roughly 0.6 s for a short reply, a little over 1 s for several thousand characters).
 * If the row leaves the screen before it finishes, it counts as revealed and never replays.
 */
@Composable
private fun TypewriterMarkdown(text: String, onDone: () -> Unit) {
    var shown by remember(text) { mutableIntStateOf(0) }
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(text) {
        var count = 0f
        var last = withFrameNanos { it }
        while (count < text.length) {
            val now = withFrameNanos { it }
            val frames = ((now - last) / 16_666_667f).coerceIn(0.25f, 4f)
            last = now
            count += (1.5f + count * 0.045f + text.length / 600f) * frames
            shown = count.toInt().coerceAtMost(text.length)
        }
        done()
    }
    DisposableEffect(Unit) { onDispose { done() } }
    MarkdownContent(text.substring(0, shown.coerceIn(0, text.length)), compact = true)
}

@Composable
private fun StepsGroup(steps: List<Step>, expanded: Boolean, onToggle: () -> Unit, onOpenStep: (Step) -> Unit) {
    val rotation by animateFloatAsState(if (expanded) 90f else 0f, animationSpec = tween(180), label = "chevron")
    Column {
        Row(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onToggle)
                .padding(start = 2.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (steps.size == 1) "1 step" else "${steps.size} steps",
                style = MaterialTheme.typography.labelMedium,
                color = Palette.TextTertiary,
            )
            Spacer(Modifier.width(4.dp))
            AppIcon(R.drawable.ic_chevron_right, if (expanded) "Collapse" else "Expand", tint = Palette.TextTertiary, size = 15.dp, modifier = Modifier.rotate(rotation))
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(200)) + fadeIn(tween(160)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
        ) {
            Column(Modifier.padding(top = 2.dp)) {
                steps.forEach { StepRow(it, onClick = { onOpenStep(it) }) }
            }
        }
    }
}

@Composable
private fun StepRow(step: Step, onClick: () -> Unit) {
    val failed = step.status == StepStatus.FAILED
    val awaiting = step.status == StepStatus.AWAITING_APPROVAL
    val muted = step.status == StepStatus.RUNNING || step.status == StepStatus.DENIED
    val tint = when {
        failed -> Palette.Danger
        awaiting -> Palette.Warning
        else -> Palette.IconMuted
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(start = 2.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when {
                failed && step.kind != StepKind.PERMISSION -> AppIcon(R.drawable.ic_warning, "Failed", tint = tint, size = 17.dp)
                step.kind == StepKind.FETCH -> Favicon(step.payload, size = 15.dp)
                else -> AppIcon(iconFor(step.kind), null, tint = tint, size = 17.dp)
            }
        }
        Spacer(Modifier.width(12.dp))
        FadingText(
            text = step.payload,
            style = if (step.kind == StepKind.SHELL) MonoSmall else MaterialTheme.typography.bodyMedium,
            color = if (muted) Palette.TextTertiary else Palette.TextSecondary,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        AppIcon(R.drawable.ic_chevron_right, null, tint = Palette.TextTertiary, size = 14.dp)
    }
}

fun iconFor(kind: StepKind): Int = when (kind) {
    StepKind.SEARCH -> R.drawable.ic_search
    StepKind.FETCH -> R.drawable.ic_globe
    StepKind.SHELL -> R.drawable.ic_terminal
    StepKind.FILE_READ -> R.drawable.ic_file
    StepKind.FILE_WRITE -> R.drawable.ic_file_edit
    StepKind.PERMISSION -> R.drawable.ic_shield
}

@Composable
private fun ApprovalCard(request: ApprovalRequest, onDecision: (Boolean) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .softSurface(RoundedCornerShape(20.dp), Palette.Surface, elevation = 4.dp)
            .padding(start = 16.dp, end = 10.dp, top = 14.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(R.drawable.ic_shield, null, tint = Palette.Warning, size = 18.dp)
            Spacer(Modifier.width(12.dp))
            FadingText(request.summary, MonoSmall, Palette.TextPrimary, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(request.reason, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary, modifier = Modifier.padding(start = 30.dp).weight(1f))
            PillButton("Deny", primary = false) { onDecision(false) }
            Spacer(Modifier.width(8.dp))
            PillButton("Allow", primary = true) { onDecision(true) }
        }
    }
}

@Composable
fun PillButton(label: String, primary: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (primary) Palette.OnAccent else Palette.TextPrimary,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (primary) Palette.Accent else Palette.SurfaceHighest)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
    )
}

@Composable
fun FilePill(file: FileAttachment, onClick: () -> Unit) {
    Row(
        Modifier
            .widthIn(max = 340.dp)
            .softSurface(RoundedCornerShape(18.dp), Palette.SurfaceHigh, elevation = 4.dp)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(R.drawable.ic_file, "File", tint = Palette.IconActive, size = 20.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f, fill = false)) {
            FadingText(file.name, MaterialTheme.typography.titleSmall, Palette.TextPrimary)
            val meta = fileMeta(file)
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary)
        }
    }
}

private fun fileMeta(file: FileAttachment): String {
    val age = System.currentTimeMillis() - file.updatedAt
    val time = when {
        age < 60_000 -> "Updated now"
        age < 3_600_000 -> "Updated ${age / 60_000}m ago"
        age < 86_400_000 -> "Updated ${age / 3_600_000}h ago"
        else -> "Updated ${age / 86_400_000}d ago"
    }
    return if (file.baseSnapshot != null) "$time  +${file.added} \u2212${file.removed}" else time
}

@Composable
private fun SourceCluster(sources: List<SourceRef>, onOpen: (SourceRef) -> Unit, onMore: (List<SourceRef>) -> Unit) {
    val visible = sources.distinctBy { it.host }.take(3)
    val remaining = sources.filterNot { s -> visible.any { it.url == s.url } }
    Row(
        Modifier
            .softSurface(RoundedCornerShape(50), Palette.Surface, elevation = 3.dp)
            .horizontalScroll(rememberScrollState())
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        visible.forEach { s ->
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { onOpen(s) }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Favicon(s.host, size = 14.dp)
                Spacer(Modifier.width(7.dp))
                Text(s.host, style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary, maxLines = 1)
            }
        }
        if (remaining.isNotEmpty()) {
            Text(
                "+${remaining.size}",
                style = MaterialTheme.typography.labelMedium,
                color = Palette.TextSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { onMore(remaining) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}
