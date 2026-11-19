package com.sebialex.agent.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.sebialex.agent.R
import com.sebialex.agent.domain.agent.ApprovalRequest
import com.sebialex.agent.domain.model.FileAttachment
import com.sebialex.agent.domain.model.SourceRef
import com.sebialex.agent.domain.model.Step
import com.sebialex.agent.domain.model.StepKind
import com.sebialex.agent.domain.model.StepStatus
import com.sebialex.agent.domain.model.Turn
import com.sebialex.agent.domain.model.TurnText
import com.sebialex.agent.ui.components.AppIcon
import com.sebialex.agent.ui.components.Favicon
import com.sebialex.agent.ui.components.FadingText
import com.sebialex.agent.ui.components.busyShimmer
import com.sebialex.agent.ui.components.bubbleEnter
import com.sebialex.agent.ui.markdown.MarkdownContent
import com.sebialex.agent.ui.theme.MonoSmall
import com.sebialex.agent.ui.theme.Palette
import com.sebialex.agent.ui.theme.softSurface

@Composable
fun UserMessage(
    turn: Turn,
    /** Resolves an `@path` mention to its workspace file; null when it is not an existing file. */
    resolveFile: (String) -> java.io.File? = { null },
    /** The user's chosen bubble look; empty id keeps the plain surface. */
    bubbleStyleId: String = "",
    onOpenFile: (String) -> Unit = {},
    /** A tap on the bubble: shows or hides its action row. Null: the message has no actions. */
    onTap: (() -> Unit)? = null,
    /** The action row under the bubble is open. */
    actionsOpen: Boolean = false,
    /** Edit and Delete are possible now (not while an answer runs). */
    canModify: Boolean = false,
    onCopy: () -> Unit = {},
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {},
    /** The user's send-motion setting; only a just-sent message animates. */
    motion: Boolean = true,
) {
    val tap = rememberUpdatedState(onTap)
    // Imports become previews and their @path text is not repeated. Files @-mentioned while
    // typing stay in the text (highlighted) and get a compact chip under the bubble.
    val resolved = remember(turn.text) {
        com.sebialex.agent.domain.agent.Mentions.paths(turn.text).mapNotNull { p -> resolveFile(p)?.let { p to it } }
    }
    val files = remember(resolved) { resolved.filter { it.first.startsWith("imports/") } }
    val mentioned = remember(resolved) { resolved.filterNot { it.first.startsWith("imports/") } }
    val body = remember(turn.text, files) {
        if (files.isEmpty()) turn.text else com.sebialex.agent.domain.agent.Mentions.strip(turn.text, files.map { it.first })
    }
    val styledBody = remember(body, mentioned) {
        androidx.compose.ui.text.buildAnnotatedString {
            append(body)
            if (mentioned.isNotEmpty()) com.sebialex.agent.domain.agent.Mentions.ranges(body).forEach { r ->
                addStyle(androidx.compose.ui.text.SpanStyle(color = Palette.Link, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), r.first, r.last + 1)
            }
        }
    }
    val bubble = com.sebialex.agent.ui.theme.BubbleStyles.byId(bubbleStyleId)
    val bubbleBrush = remember(bubble.id) { com.sebialex.agent.ui.theme.BubbleStyles.brush(bubble) }
    val bubbleText = remember(bubble.id) { com.sebialex.agent.ui.theme.BubbleStyles.textColor(bubble, Palette.TextPrimary, Palette.Surface) }
    val bubbleShape = RoundedCornerShape(22.dp)
    val bubbleModifier = if (bubbleBrush == null) {
        Modifier.softSurface(bubbleShape, Palette.SurfaceHigh, elevation = 2.dp)
    } else {
        Modifier
            .shadow(2.dp, bubbleShape)
            .clip(bubbleShape)
            .background(bubbleBrush)
            .then(com.sebialex.agent.ui.theme.BubbleStyles.effectModifier(bubble, bubbleShape))
    }
    val fresh = remember(turn.id) { System.currentTimeMillis() - turn.createdAt < 2000 }
    Column(
        Modifier.fillMaxWidth().padding(start = 56.dp).bubbleEnter(motion && fresh),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        files.forEach { (path, file) -> ImportCard(path, file, onClick = { onOpenFile(path) }) }
        // The bubble and its action row as one block, so the row unfolds right from the bubble's
        // bottom edge (no gap of the outer spacing while it is folded away).
        if (body.isNotBlank()) Column(horizontalAlignment = Alignment.End) {
            androidx.compose.foundation.text.selection.SelectionContainer {
            Text(
                styledBody,
                style = MaterialTheme.typography.bodyLarge,
                color = bubbleText,
                modifier = bubbleModifier
                    .clip(bubbleShape)
                    .then(
                        // A plain tap only: drags still scroll the list and long-press stays free.
                        if (onTap == null) Modifier
                        else Modifier.pointerInput(Unit) { detectTapGestures(onTap = { tap.value?.invoke() }) },
                    )
                    .padding(horizontal = 16.dp, vertical = 11.dp),
            )
            }
            if (onTap != null) MessageActionRow(actionsOpen, canModify, onCopy, onEdit, onDelete)
        }
        mentioned.forEach { (path, file) -> MentionChip(file, onClick = { onOpenFile(path) }) }
    }
}

/** A workspace file @-mentioned in your message: small thumbnail or type icon and its name. */
@Composable
private fun MentionChip(file: java.io.File, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .widthIn(max = 260.dp)
            .clip(shape)
            .background(Palette.Surface)
            .clickable(onClick = onClick)
            .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaThumb(file, 22.dp, corner = 11.dp)
        Spacer(Modifier.width(8.dp))
        FadingText(file.name, MaterialTheme.typography.labelMedium, Palette.TextSecondary)
    }
}

/**
 * A message sent while the agent was busy: shown like your own message, dimmed, with a small
 * "Queued" badge and a remove button. It is sent as soon as the running turn finishes.
 * After Stop the queue is [paused]: the badge says so and a send button sends it now.
 */
@Composable
fun QueuedBubble(
    message: com.sebialex.agent.ui.chat.QueuedMessage,
    resolveFile: (String) -> java.io.File?,
    onRemove: () -> Unit,
    paused: Boolean = false,
    onSendNow: () -> Unit = {},
) {
    val paths = remember(message.task) { com.sebialex.agent.domain.agent.Mentions.paths(message.task).filter { resolveFile(it) != null } }
    val body = remember(message.task, paths) { com.sebialex.agent.domain.agent.Mentions.strip(message.task, paths) }
    Column(
        Modifier.fillMaxWidth().padding(start = 56.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(
            Modifier
                .alpha(0.72f)
                .softSurface(RoundedCornerShape(22.dp), Palette.SurfaceHigh, elevation = 1.dp)
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        ) {
            if (paths.isNotEmpty()) Text(
                paths.joinToString("  ") { "\u2022 " + it.substringAfterLast('/') },
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextTertiary,
                maxLines = 2,
            )
            if (body.isNotBlank()) Text(body, style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (paused) com.sebialex.agent.ui.components.QuietIconButton(
                R.drawable.ic_arrow_up, "Send now", onClick = onSendNow, size = 28.dp, iconSize = 14.dp, tint = Palette.IconActive,
            )
            Text(
                if (paused) "Paused" else "Queued",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Palette.SurfaceHighest)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
            com.sebialex.agent.ui.components.QuietIconButton(
                R.drawable.ic_close, "Remove queued message", onClick = onRemove, size = 28.dp, iconSize = 13.dp, tint = Palette.IconMuted,
            )
        }
    }
}

/**
 * Fixed visual order: execution row, answer, file attachments, website sources.
 */
@Composable
fun AssistantTurn(
    turn: Turn,
    pendingApproval: ApprovalRequest?,
    onOpenStep: (Step) -> Unit,
    onOpenFile: (FileAttachment) -> Unit,
    onOpenSource: (SourceRef) -> Unit,
    onMoreSources: (List<SourceRef>) -> Unit,
    onApproval: (Boolean) -> Unit,
    reveal: Boolean = false,
    onRevealed: () -> Unit = {},
    /** Resolves a workspace path to its file (for artifact chips); null when it no longer exists. */
    resolveFile: (String) -> java.io.File? = { null },
    /** The app was killed during this answer and it is the last one: "Continue from where it stopped". */
    onContinue: (() -> Unit)? = null,
    /** Id of the question (ask_user) that waits for a reply, if any; [onAnswer] replies to it. */
    askingId: String? = null,
    onAnswer: (String) -> Unit = {},
    /** Web apps of this turn (preview_webapp): whether each folder still exists, and opening one. */
    previewAvailable: (com.sebialex.agent.domain.model.WebPreview) -> Boolean = { true },
    onOpenPreview: (com.sebialex.agent.domain.model.WebPreview) -> Unit = {},
    /** The stop button on one of this answer's subagent cards. */
    onStopSubagent: (String) -> Unit = {},
    /** The user's send-motion setting; only a just-received answer animates. */
    motion: Boolean = true,
    /** A tap on the answer: shows or hides its action row. Null: the answer has no actions. */
    onTap: (() -> Unit)? = null,
    actionsOpen: Boolean = false,
    /** Regenerate is possible now (not while an answer runs). */
    canModify: Boolean = false,
    onCopy: () -> Unit = {},
    onRegenerate: () -> Unit = {},
) {
    val fresh = remember(turn.id) { System.currentTimeMillis() - turn.createdAt < 2000 }
    Column(Modifier.fillMaxWidth().bubbleEnter(motion && fresh), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // The turn reads in the order it happened: what the model said, then the steps that
        // followed below it (one collapsible group per burst), then the next text below them.
        // Only the step still in flight stays below its group so live thinking / a running tool
        // remains seen. Turns saved by older builds (no blocks) keep the fixed answer layout.
        val ordered = remember(turn.steps) { stepSegments(turn.steps).flatten() }
        val live = ordered.lastOrNull()?.takeIf { it.status == StepStatus.RUNNING || it.status == StepStatus.AWAITING_APPROVAL }
        if (turn.blocks.isEmpty()) {
            val grouped = if (live != null) ordered.dropLast(1) else ordered
            if (grouped.isNotEmpty()) StepsGroup(grouped, grouped.size, groupKey(turn, grouped.first().id), fresh, onOpenSource, resolveFile)
            if (live != null) StepRow(live, onOpenSource, resolveFile)
            if (pendingApproval != null && turn.steps.any { it.id == pendingApproval.stepId }) {
                ApprovalCard(pendingApproval, onApproval)
            }
            turn.questions.forEach { q -> QuestionCard(q, waiting = q.id == askingId && q.answer == null, onAnswer = onAnswer) }
            if (turn.text.isNotBlank()) {
                Column {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Box(
                            Modifier.then(
                                // A plain tap only: long-press still selects text, drags still scroll.
                                if (onTap == null) Modifier
                                else Modifier.pointerInput(turn.id) { detectTapGestures(onTap = { onTap.invoke() }) },
                            ),
                        ) {
                            if (reveal) TypewriterMarkdown(turn.text, onRevealed) else MarkdownContent(turn.text, compact = true)
                        }
                    }
                    AnswerActionRow(actionsOpen, canModify, onCopy, onRegenerate)
                }
            }
        } else {
            val sections = remember(turn.steps, turn.blocks) { turnSections(turn.blocks, turn.steps) }
            val lastText = sections.indexOfLast { it is TurnSection.Text }
            sections.forEachIndexed { i, section ->
                when (section) {
                    is TurnSection.Steps -> {
                        val running = section.steps.lastOrNull()?.takeIf { it.id == live?.id }
                        val grouped = if (running != null) section.steps.dropLast(1) else section.steps
                        if (grouped.isNotEmpty()) {
                            StepsGroup(grouped, grouped.size, groupKey(turn, grouped.first().id), fresh, onOpenSource, resolveFile)
                        }
                        if (running != null) StepRow(running, onOpenSource, resolveFile)
                    }
                    is TurnSection.Text -> if (section.block.text.isNotBlank()) {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            if (reveal && i == lastText) TypewriterMarkdown(section.block.text, onRevealed)
                            else MarkdownContent(section.block.text, compact = true)
                        }
                    }
                }
            }
            if (pendingApproval != null && turn.steps.any { it.id == pendingApproval.stepId }) {
                ApprovalCard(pendingApproval, onApproval)
            }
            turn.questions.forEach { q -> QuestionCard(q, waiting = q.id == askingId && q.answer == null, onAnswer = onAnswer) }
        }
        // Subagents of this answer: compact live cards under the message, their steps apart.
        SubagentCards(turn.subagents, onOpenStep, onStopSubagent)
        // Files and sources follow the answer once it has been revealed.
        // A file that is attached as an artifact is not repeated as a file pill below it.
        val pills = remember(turn.attachments, turn.artifacts) {
            val attached = turn.artifacts.map { it.path }.toSet()
            turn.attachments.filterNot { it.path in attached }
        }
        if (pills.isNotEmpty() || turn.sources.isNotEmpty() || turn.artifacts.isNotEmpty()) AnimatedVisibility(visible = !reveal, enter = fadeIn(tween(220)), exit = fadeOut(tween(1))) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Attached artifacts first: what the agent produced for you, one tap away.
                if (turn.artifacts.isNotEmpty()) ArtifactChips(turn.artifacts, resolveFile, onOpen = onOpenFile)
                if (pills.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        pills.forEach { FilePill(it, onClick = { onOpenFile(it) }) }
                    }
                }
                if (turn.sources.isNotEmpty()) {
                    SourceCluster(turn.sources, onOpenSource, onMoreSources)
                }
            }
        }
        if (turn.previews.isNotEmpty()) AnimatedVisibility(visible = !reveal, enter = fadeIn(tween(220)), exit = fadeOut(tween(1))) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                turn.previews.forEach { p ->
                    com.sebialex.agent.ui.preview.PreviewCard(p, available = previewAvailable(p), onOpen = { onOpenPreview(p) })
                }
            }
        }
        if (onContinue != null) ContinueChip(onContinue)
    }
}

/**
 * Under an answer the app was killed in the middle of: one tap starts a new run on the same
 * request, with this conversation (and the steps already done) as context.
 */
@Composable
private fun ContinueChip(onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .softSurface(shape, Palette.SurfaceHigh, elevation = 2.dp)
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(R.drawable.ic_refresh, null, tint = Palette.IconActive, size = 16.dp)
        Spacer(Modifier.width(8.dp))
        Text("Continue from where it stopped", style = MaterialTheme.typography.labelLarge, color = Palette.TextPrimary)
    }
}

/**
 * Splits a turn's steps into display segments in their real order: a think block is its own
 * segment, and consecutive tool calls form one segment. Reasoning with no text (or text already
 * shown by an earlier block) is dropped, so there are no placeholder or duplicate rows.
 */
internal fun stepSegments(steps: List<Step>): List<List<Step>> {
    val seen = HashSet<String>()
    val out = mutableListOf<MutableList<Step>>()
    for (st in steps) {
        if (st.kind == StepKind.REASONING) {
            val text = st.output.trim()
            if (text.isEmpty() || !seen.add(text)) continue
            out += mutableListOf(st)
        } else {
            val last = out.lastOrNull()
            if (last != null && last[0].kind != StepKind.REASONING) last += st else out += mutableListOf(st)
        }
    }
    return out
}

/** What a turn shows while it reads in real order: one burst of actions, or one text block. */
internal sealed interface TurnSection {
    data class Steps(val steps: List<Step>) : TurnSection
    data class Text(val block: TurnText) : TurnSection
}

/**
 * Merges a turn's actions and text blocks into one chronological plan: by time, steps before
 * text on equal times (thinking precedes the narration of the same call). Consecutive actions
 * between two texts form one collapsible group — a burst of tool calls with no thinking row in
 * between must still split at the text it was answering to. A duplicate or empty thinking row is
 * dropped, and text written in consecutive calls with nothing built in between reads as one.
 * Empty when the turn has no blocks (older saves keep the fixed layout).
 */
internal fun turnSections(blocks: List<TurnText>, steps: List<Step>): List<TurnSection> {
    if (blocks.isEmpty()) return emptyList()
    val timeline = ArrayList<Triple<Long, Int, TurnSection>>()
    val seenThoughts = HashSet<String>()
    steps.forEachIndexed { i, st ->
        if (st.kind == StepKind.REASONING) {
            val thought = st.output.trim()
            if (thought.isEmpty() || !seenThoughts.add(thought)) return@forEachIndexed
        }
        timeline.add(Triple(st.startedAt, i, TurnSection.Steps(listOf(st))))
    }
    blocks.forEachIndexed { i, b ->
        if (b.text.isNotBlank()) timeline.add(Triple(b.at, steps.size + i, TurnSection.Text(b)))
    }
    val sorted = timeline.sortedWith(compareBy({ it.first }, { it.second })).map { it.third }
    val out = ArrayList<TurnSection>()
    for (s in sorted) {
        val last = out.lastOrNull()
        val merged = when {
            s is TurnSection.Steps && last is TurnSection.Steps -> TurnSection.Steps(last.steps + s.steps)
            s is TurnSection.Text && last is TurnSection.Text ->
                TurnSection.Text(TurnText(last.block.id, last.block.text + "\n\n" + s.block.text, last.block.at))
            else -> null
        }
        if (merged != null) out[out.lastIndex] = merged else out.add(s)
    }
    return out
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

/** Stable identity of one burst inside [turn]: no two groups share their open state. */
private fun groupKey(turn: Turn, firstStepId: String) = "${turn.id}:$firstStepId"

/**
 * One collapsible burst of actions. The open state is the group's own ([groupKey] keeps it
 * per turn and per burst), so extending one block never moves the others; [initiallyExpanded]
 * covers the old behavior where a just-sent turn's group starts open.
 */
@Composable
private fun StepsGroup(
    steps: List<Step>,
    actionCount: Int,
    groupKey: String,
    initiallyExpanded: Boolean,
    onOpenSource: (SourceRef) -> Unit,
    resolveFile: (String) -> java.io.File?,
) {
    var expanded by rememberSaveable(groupKey) { mutableStateOf(initiallyExpanded) }
    val rotation by animateFloatAsState(if (expanded) 90f else 0f, animationSpec = tween(180), label = "chevron")
    Column {
        Row(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = { expanded = !expanded })
                .padding(start = 2.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (actionCount == 1) "1 step" else "$actionCount steps",
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
                steps.forEach { StepRow(it, onOpenSource, resolveFile) }
            }
        }
    }
}

/**
 * One action, expanded in place: tapping the row reveals what it did — a web search lists the
 * sites it found, anything else shows its real input and output — instead of opening a sheet.
 * A running step carries the same moving gradient as a generating conversation title.
 */
@Composable
private fun StepRow(step: Step, onOpenSource: (SourceRef) -> Unit, resolveFile: (String) -> java.io.File? = { null }) {
    if (step.kind == StepKind.REASONING) {
        ThinkingSection(step)
        return
    }
    var open by rememberSaveable(step.id) { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (open) 90f else 0f, animationSpec = tween(180), label = "stepChevron")
    val failed = step.status == StepStatus.FAILED
    val awaiting = step.status == StepStatus.AWAITING_APPROVAL
    val running = step.status == StepStatus.RUNNING
    val muted = running || step.status == StepStatus.DENIED
    val tint = when {
        failed -> Palette.Danger
        awaiting -> Palette.Warning
        else -> Palette.IconMuted
    }
    // A command that was not run because its program is missing: a short readable note under
    // the row (what is missing and the install command) instead of a bare failure.
    val missingTool = remember(step.output, step.status) {
        if (failed && step.exitCode == 127 && MISSING_MARK in step.output) {
            val lines = step.output.lines()
            val what = lines.firstOrNull()?.substringBefore(MISSING_MARK)?.trim()?.trimEnd(',')
                ?.removeSuffix(" is not installed")?.removePrefix("Not installed: ")?.trim().orEmpty()
            val install = lines.drop(1).map { it.trim() }.firstOrNull { it.startsWith("pip ") || it.startsWith("pkg ") || it.startsWith("apt ") }
            (if (what.isNotEmpty()) "$what \u2014 not installed" else "Not installed") + (install?.let { " \u00b7 $it" } ?: "")
        } else null
    }
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 38.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable { open = !open }
                .padding(start = 2.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                if (step.kind == StepKind.FETCH) Favicon(step.payload, size = 15.dp)
                else AppIcon(iconFor(step.kind), null, tint = tint, size = 17.dp)
            }
            Spacer(Modifier.width(12.dp))
            FadingText(
                text = stepLabel(step),
                style = if (step.tool == "shell" || step.tool == "python") MonoSmall else MaterialTheme.typography.bodyMedium,
                color = if (muted) Palette.TextTertiary else Palette.TextSecondary,
                modifier = Modifier.weight(1f).then(if (running) Modifier.busyShimmer() else Modifier),
            )
            // Ran without asking (auto-approve): a tiny shield in the row's own muted tint.
            if (step.autoApproved) {
                Spacer(Modifier.width(6.dp))
                AppIcon(R.drawable.ic_shield, "Auto-approved", tint = Palette.TextTertiary, size = 12.dp)
            }
            Spacer(Modifier.width(8.dp))
            AppIcon(R.drawable.ic_chevron_right, if (open) "Collapse" else "Expand", tint = Palette.TextTertiary, size = 14.dp, modifier = Modifier.rotate(rotation))
        }
        if (missingTool != null) {
            Text(
                missingTool,
                style = MonoSmall,
                color = Palette.Warning,
                maxLines = 2,
                modifier = Modifier
                    .padding(start = 34.dp, end = 8.dp, bottom = 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Palette.Warning.copy(alpha = 0.08f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(tween(200)) + fadeIn(tween(160)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
        ) {
            StepDetail(step, onOpenSource, resolveFile)
        }
    }
}

/** What a step did, in words: "Generating image" while it runs, "Generated image" once it is done. */
internal fun stepLabel(step: Step): String {
    val busy = step.status == StepStatus.RUNNING || step.status == StepStatus.AWAITING_APPROVAL
    fun t(ing: String, done: String) = if (busy) ing else done
    val name = step.payload.substringAfterLast('/')
    return when (step.tool) {
        "web_search" -> t("Searching", "Searched") + " \u201c${step.payload}\u201d"
        "web_fetch" -> t("Reading ", "Read ") + step.payload
        "download_file" -> t("Downloading", "Downloaded")
        "http_request" -> t("Calling ", "Called ") + step.payload
        "shell", "python" -> step.payload
        "read_file" -> t("Reading ", "Read ") + name
        "write_file" -> t("Writing ", "Wrote ") + name
        "edit_file" -> t("Editing ", "Edited ") + name
        "list_files" -> t("Listing files", "Listed files")
        "search_files" -> t("Searching files", "Searched files")
        "move_file" -> t("Moving ", "Moved ") + step.payload
        "copy_file" -> t("Copying ", "Copied ") + step.payload
        "delete_file" -> t("Deleting ", "Deleted ") + name
        "file_info", "view_image" -> step.payload
        "zip_files" -> t("Zipping files", "Zipped files")
        "unzip_file" -> t("Unzipping", "Unzipped")
        "generate_image" -> t("Generating image", "Generated image")
        "generate_video" -> t("Generating video", "Generated video")
        "check_tools" -> t("Checking tools", "Checked tools")
        "device_info" -> t("Reading device info", "Read device info")
        "attach_files" -> t("Attaching files", "Attached files")
        "preview_webapp" -> t("Preparing preview", "Prepared preview")
        "conversation_memory" -> t("Recalling past chats", "Recalled past chats")
        "ask_user" -> t("Asking you", "Asked you")
        "todo" -> t("Updating the list", "Updated the list")
        "spawn_subagent" -> t("Starting a subagent", "Started a subagent")
        "main_conversation" -> t("Reading the conversation", "Read the conversation")
        "vision_intent" -> t("Checking the image", "Checked the image")
        else -> step.payload
    }
}

/**
 * The expanded body of a step. A web search becomes a vertical list of the sites it found
 * (favicon, title, host); tapping one opens its preview. Every other step shows its real
 * input/output, scrollable, with the exit code when there is one.
 */
@Composable
private fun StepDetail(step: Step, onOpenSource: (SourceRef) -> Unit, resolveFile: (String) -> java.io.File?) {
    Column(Modifier.padding(start = 32.dp, end = 8.dp, top = 2.dp, bottom = 8.dp)) {
        // A generated picture shows as a small squircle preview, like the search and thinking rows.
        val made = if (step.tool == "generate_image" || step.tool == "generate_video") step.path?.let { resolveFile(it) } else null
        if (made != null) {
            Box(
                Modifier.size(84.dp).clip(RoundedCornerShape(20.dp)).background(Palette.SurfaceHigh),
                contentAlignment = Alignment.Center,
            ) { MediaThumb(made, 84.dp) }
        }
        if (step.kind == StepKind.SEARCH && step.hits.isNotEmpty()) {
            step.hits.forEach { hit ->
                val host = com.sebialex.agent.data.web.hostOf(hit.url)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onOpenSource(SourceRef(hit.url, host, hit.title, hit.snippet)) }
                        .padding(vertical = 7.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Favicon(host, size = 15.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        FadingText(
                            hit.title.ifBlank { host },
                            MaterialTheme.typography.bodySmall,
                            Palette.TextSecondary,
                        )
                        Text(host, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary, maxLines = 1)
                    }
                }
            }
        } else {
            val body = step.output.ifBlank { step.input }
            if (body.isNotBlank()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Palette.CodeBackground)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(body, style = MonoSmall, color = Palette.TextTertiary)
                }
            }
            step.exitCode?.let {
                Text(
                    "exit code $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (it == 0) Palette.TextTertiary else Palette.Danger,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** Marker in the output of a shell step that was not run because a program is missing (see ToolCheck). */
private const val MISSING_MARK = "so the command was not run"

/**
 * The quiet reasoning row, with no label in front. While the model is thinking, its raw reasoning streams into the row
 * as words: a window about one line tall, pinned to the newest text, with the older line fading
 * out at the top and the right edge fading when a line runs long. When thinking ends the row
 * keeps a single line of the reasoning, fading out at its tail. Tapping opens the full text.
 */
@Composable
private fun ThoughtRow(step: Step, onClick: () -> Unit) {
    // Collapse whitespace so the reasoning reads as one flowing line of words.
    val flowing = remember(step.output) { step.output.replace(Regex("\\s+"), " ").trim() }
    val style = MaterialTheme.typography.bodySmall
    val lineHeight = with(LocalDensity.current) { style.lineHeight.toDp() }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 38.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(start = 2.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Only used while the model is thinking: newest words at the bottom of a ~1.4 line
        // window; whatever overflows fades away. No chevron here: the "Thinking" header above
        // already has one, and two expand affordances for the same content read as two sections.
        Box(
            Modifier
                .weight(1f)
                .height(lineHeight * 1.4f)
                .clipToBounds()
                .fadeTopEdge(),
        ) {
            Text(
                flowing.takeLast(LIVE_THOUGHT_CHARS),
                style = style,
                color = Palette.TextTertiary,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomStart)
                    .wrapContentHeight(align = Alignment.Bottom, unbounded = true),
            )
        }
    }
}

/**
 * The model's thinking for a turn that also called tools, as its own section under the tool
 * calls: a quiet "Thinking" / "Thought for 12s" header with a chevron. While the model is still
 * thinking the live one-line window shows under the header; tapping the header or the window
 * expands the full reasoning in place — no sheet.
 */
@Composable
private fun ThinkingSection(step: Step) {
    val live = step.status == StepStatus.RUNNING
    var open by androidx.compose.runtime.saveable.rememberSaveable(step.id) { androidx.compose.runtime.mutableStateOf(false) }
    val rotation by animateFloatAsState(if (open) 90f else 0f, animationSpec = tween(180), label = "thinkingChevron")
    val seconds = step.finishedAt?.let { ((it - step.startedAt) / 1000).coerceAtLeast(1) }
    val label = when {
        live -> "Thinking"
        seconds != null -> "Thought for ${seconds}s"
        else -> "Thought"
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable { open = !open }
                .padding(start = 2.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(R.drawable.ic_lightbulb, null, tint = Palette.TextTertiary, size = 14.dp)
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = Palette.TextTertiary)
            Spacer(Modifier.width(4.dp))
            AppIcon(R.drawable.ic_chevron_right, if (open) "Hide thinking" else "Show thinking", tint = Palette.TextTertiary, size = 15.dp, modifier = Modifier.rotate(rotation))
        }
        // Live and collapsed: the moving one-line window, so thinking is still visible as it streams.
        if (live && !open && step.output.isNotBlank()) ThoughtRow(step) { open = !open }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(tween(200)) + fadeIn(tween(160)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
        ) {
            val text = remember(step.output) { step.output.trim() }
            Box(
                Modifier
                    .padding(start = 8.dp, top = 2.dp, bottom = 4.dp)
                    .fillMaxWidth()
                    .drawWithContent {
                        drawContent()
                        // A thin rule on the left marks the block as the model's own thinking.
                        drawRect(Palette.SurfaceHighest, size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height))
                    }
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            ) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextTertiary,
                )
            }
        }
    }
}

/** Live reasoning is a moving window; only the tail ever needs to be laid out. */
private const val LIVE_THOUGHT_CHARS = 360

/** Fades the top of the content out (destination-in mask), so older text dissolves upward. */
private fun Modifier.fadeTopEdge(): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                0.55f to Color.Black,
                1f to Color.Black,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

fun iconFor(kind: StepKind): Int = when (kind) {
    StepKind.SEARCH -> R.drawable.ic_search
    StepKind.FETCH -> R.drawable.ic_globe
    StepKind.SHELL -> R.drawable.ic_terminal
    StepKind.FILE_READ -> R.drawable.ic_file
    StepKind.FILE_WRITE -> R.drawable.ic_file_edit
    StepKind.PERMISSION -> R.drawable.ic_shield
    // Never drawn in a row (the row shows an ellipsis); kept so the mapping stays total.
    StepKind.REASONING -> R.drawable.ic_more
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

/**
 * Files the agent attached to its answer: compact chips (tiny thumbnail or type icon, name,
 * size) that wrap onto more lines, never a big inline preview. Tapping opens the file viewer
 * with its image viewer / media player. A file deleted since then shows as unavailable.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ArtifactChips(artifacts: List<FileAttachment>, resolveFile: (String) -> java.io.File?, onOpen: (FileAttachment) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        artifacts.forEach { a ->
            val file = remember(a.path, a.updatedAt) { resolveFile(a.path) }
            val shape = RoundedCornerShape(14.dp)
            Row(
                Modifier
                    .widthIn(max = 260.dp)
                    .softSurface(shape, Palette.SurfaceHigh, elevation = 2.dp)
                    .clip(shape)
                    .clickable(enabled = file != null) { onOpen(a.copy(baseSnapshot = null)) }
                    .padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (file != null) {
                    MediaThumb(file, 28.dp)
                } else {
                    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                        AppIcon(R.drawable.ic_file, null, tint = Palette.TextTertiary, size = 16.dp)
                    }
                }
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f, fill = false)) {
                    FadingText(a.name, MaterialTheme.typography.labelLarge, if (file != null) Palette.TextPrimary else Palette.TextTertiary)
                    Text(
                        file?.let { formatBytes(it.length()) } ?: "No longer in the workspace",
                        style = MaterialTheme.typography.labelSmall,
                        color = Palette.TextTertiary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
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
