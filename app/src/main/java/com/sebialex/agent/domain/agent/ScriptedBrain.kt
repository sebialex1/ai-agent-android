package com.sebialex.agent.domain.agent

import com.sebialex.agent.domain.model.SearchHit
import com.sebialex.agent.domain.model.ToolCall
import java.util.Locale

/**
 * Deterministic planner that stands in for an LLM. It classifies the task, emits a realistic
 * sequence of tool calls and composes the final answer from the actual tool results.
 */
class ScriptedBrain : AgentBrain {

    private enum class Intent { WRITE_DOC, UPDATE_DOC, CLEANUP, INSTALL, LIST, RUN, RESEARCH }

    override suspend fun next(ctx: BrainContext): BrainDecision {
        val intent = classify(ctx)
        val obs = ctx.observations
        val n = obs.size
        fun id() = "c${n + 1}"
        return when (intent) {
            Intent.RESEARCH -> {
                val q = query(ctx.task)
                when {
                    n == 0 -> act(ToolCall.WebSearch(id(), q))
                    n in 1..3 -> nextFetch(ctx, 3)?.let { act(ToolCall.WebFetch(id(), it)) } ?: answerResearch(ctx)
                    else -> answerResearch(ctx)
                }
            }
            Intent.WRITE_DOC -> {
                val q = query(ctx.task)
                val slug = slug(ctx.task)
                val searches = obs.count { it.call is ToolCall.WebSearch }
                val fetches = obs.count { it.call is ToolCall.WebFetch }
                val wrote = obs.any { it.call is ToolCall.WriteFile }
                when {
                    searches == 0 -> act(ToolCall.WebSearch(id(), q))
                    searches == 1 && fetches == 0 -> act(ToolCall.WebSearch(id(), "$q architecture"))
                    fetches < 3 && nextFetch(ctx, 3) != null -> act(ToolCall.WebFetch(id(), nextFetch(ctx, 3)!!))
                    !wrote -> act(ToolCall.WriteFile(id(), "$slug.md", composeDoc(ctx)))
                    obs.last().call is ToolCall.WriteFile -> act(ToolCall.Shell(id(), "wc -l $slug.md"))
                    else -> BrainDecision.Answer("Here's your file.")
                }
            }
            Intent.UPDATE_DOC -> {
                val target = targetDoc(ctx) ?: return answer("There's no Markdown file in the workspace to update yet.")
                val read = obs.firstOrNull { it.call is ToolCall.ReadFile }
                when {
                    read == null -> act(ToolCall.ReadFile(id(), target))
                    obs.none { it.call is ToolCall.WebSearch } -> act(ToolCall.WebSearch(id(), query(ctx.task)))
                    obs.none { it.call is ToolCall.WebFetch } && nextFetch(ctx, 1) != null -> act(ToolCall.WebFetch(id(), nextFetch(ctx, 1)!!))
                    obs.none { it.call is ToolCall.WriteFile } -> act(ToolCall.WriteFile(id(), target, extendDoc(ctx, read?.result?.output ?: "")))
                    else -> {
                        val w = obs.last { it.call is ToolCall.WriteFile }.result.attachment
                        answer("Updated ${target.substringAfterLast('/')} with a new section" +
                            (if (w != null) " (+${w.added} \u2212${w.removed})." else "."))
                    }
                }
            }
            Intent.CLEANUP -> {
                val target = cleanupTarget(ctx)
                when (n) {
                    0 -> act(ToolCall.Shell(id(), "ls -la"))
                    1 -> if (target == null) answer("Nothing matched, so I left the workspace as is.") else act(ToolCall.Shell(id(), "rm -r $target"))
                    2 -> if (obs[1].result.ok) act(ToolCall.Shell(id(), "ls")) else answer("I left `$target` in place.")
                    else -> answer("Removed `$target`. The workspace now has ${ctx.workspaceFiles.size} files.")
                }
            }
            Intent.INSTALL -> {
                val pkg = Regex("install\\s+([\\w.-]+)", RegexOption.IGNORE_CASE).find(ctx.task)?.groupValues?.get(1) ?: "requests"
                when (n) {
                    0 -> act(ToolCall.Shell(id(), "pip install $pkg"))
                    1 -> if (obs[0].result.ok) act(ToolCall.Shell(id(), "cat .usr/installed.txt")) else answer("Skipped installing `$pkg`.")
                    else -> answer("`$pkg` is installed in the workspace environment.")
                }
            }
            Intent.LIST -> when (n) {
                0 -> act(ToolCall.Shell(id(), "tree"))
                else -> answer("Here's the workspace:\n\n```\n${obs[0].result.output}\n```")
            }
            Intent.RUN -> {
                val cmd = Regex("`([^`]+)`").find(ctx.task)?.groupValues?.get(1)
                    ?: ctx.task.substringAfter("run ", "").trim().ifEmpty { "ls -la" }
                when (n) {
                    0 -> act(ToolCall.Shell(id(), cmd))
                    else -> {
                        val r = obs[0].result
                        val body = r.output.ifBlank { "(no output)" }
                        answer(if (r.ok) "```\n$body\n```" else "That exited with code ${r.exitCode ?: 1}.\n\n```\n$body\n```")
                    }
                }
            }
        }
    }

    private fun act(call: ToolCall) = BrainDecision.Act(call)
    private fun answer(text: String) = BrainDecision.Answer(text)

    private fun classify(ctx: BrainContext): Intent {
        val t = ctx.task.lowercase(Locale.US)
        fun has(vararg w: String) = w.any { Regex("\\b$it\\b").containsMatchIn(t) }
        val hasDocs = ctx.workspaceFiles.any { it.endsWith(".md") }
        return when {
            t.contains('`') || t.startsWith("run ") || has("execute") -> Intent.RUN
            has("install") -> Intent.INSTALL
            has("delete", "remove", "clean", "cleanup", "rm") -> Intent.CLEANUP
            hasDocs && has("update", "revise", "extend", "append", "improve", "edit", "add") -> Intent.UPDATE_DOC
            has("plan", "draft", "write", "document", "report", "markdown", "notes", "spec", "readme", "outline", "checklist", "file") -> Intent.WRITE_DOC
            has("list", "files", "ls", "tree", "workspace") -> Intent.LIST
            else -> Intent.RESEARCH
        }
    }

    private val stop = setOf(
        "a", "an", "the", "for", "to", "of", "and", "or", "in", "on", "with", "me", "my", "please", "can", "you",
        "write", "draft", "make", "create", "plan", "document", "file", "markdown", "about", "an", "into", "it",
        "search", "find", "look", "up", "what", "is", "are", "how", "do", "i", "tell", "give", "update", "add",
        "section", "that", "this", "some", "web", "research", "summarize", "summary", "notes", "md", "short",
    )

    private fun keywords(task: String) = task.lowercase(Locale.US)
        .split(Regex("[^a-z0-9.+#-]+"))
        .filter { it.length > 1 && it !in stop && !it.endsWith(".md") }

    private fun query(task: String): String = keywords(task).take(6).joinToString(" ").ifEmpty { task.take(60) }

    private fun slug(task: String): String {
        val words = keywords(task).take(3)
        val base = if (words.isEmpty()) "notes" else words.joinToString("-")
        return if (task.lowercase(Locale.US).contains("plan")) "$base-plan" else base
    }

    private fun hits(ctx: BrainContext): List<SearchHit> = ctx.observations.flatMap { it.result.searchHits }

    private fun nextFetch(ctx: BrainContext, max: Int): String? {
        val fetched = ctx.observations.mapNotNull { (it.call as? ToolCall.WebFetch)?.url }
        if (fetched.size >= max) return null
        val fetchedHosts = fetched.map { host(it) }.toSet()
        return hits(ctx).firstOrNull { it.url !in fetched && host(it.url) !in fetchedHosts }?.url
    }

    private fun host(url: String) = url.substringAfter("://").substringBefore('/').removePrefix("www.")

    private fun pages(ctx: BrainContext) = ctx.observations.filter { it.call is ToolCall.WebFetch && it.result.ok }

    private fun answerResearch(ctx: BrainContext): BrainDecision {
        val ps = pages(ctx)
        if (ps.isEmpty()) return answer("I couldn't find anything reliable on that.")
        val hitsByUrl = hits(ctx).associateBy { it.url }
        val lines = ps.mapNotNull { o ->
            val url = (o.call as ToolCall.WebFetch).url
            hitsByUrl[url]?.snippet?.takeIf { it.isNotBlank() } ?: firstSentence(o.result.output)
        }
        val lead = lines.first()
        val rest = lines.drop(1).joinToString(" ")
        return answer(if (rest.isEmpty()) lead else "$lead\n\n$rest")
    }

    private fun firstSentence(text: String): String? = text.lineSequence()
        .map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
        ?.substringBefore(". ")?.let { if (it.endsWith(".")) it else "$it." }

    private fun title(task: String): String {
        val words = keywords(task).take(5)
        val base = words.joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.US) } }.ifEmpty { "Notes" }
        return if (task.lowercase(Locale.US).contains("plan")) "$base Plan" else base
    }

    private fun composeDoc(ctx: BrainContext): String {
        val hitsByUrl = hits(ctx).associateBy { it.url }
        val ps = pages(ctx)
        val sb = StringBuilder()
        sb.append("# ").append(title(ctx.task)).append("\n\n")
        sb.append("> ").append(ctx.task.trim().replaceFirstChar { it.uppercase() }).append("\n\n")
        sb.append("## Findings\n\n")
        ps.forEach { o ->
            val url = (o.call as ToolCall.WebFetch).url
            val hit = hitsByUrl[url]
            val text = hit?.snippet ?: firstSentence(o.result.output) ?: ""
            sb.append("- **").append(hit?.title ?: host(url)).append("**: ").append(text).append(" [").append(host(url)).append("](").append(url).append(")\n")
        }
        sb.append("\n## Plan\n\n")
        val steps = listOf(
            "Define the workspace boundary and the permission policy",
            "Wire the tool interfaces behind the agent loop",
            "Store API keys with Android Keystore-backed encryption",
            "Add an audit trail for every tool call",
            "Ship a debug build and review the audit logs",
        )
        steps.forEachIndexed { i, s -> sb.append("- [").append(if (i == 0) "x" else " ").append("] ").append(s).append('\n') }
        sb.append("\n## Interfaces\n\n")
        sb.append("```kotlin\ninterface Tool {\n    val name: String\n    suspend fun run(input: String): ToolResult\n}\n```\n\n")
        sb.append("## References\n\n| Source | Used for |\n| --- | --- |\n")
        ps.forEach { o ->
            val url = (o.call as ToolCall.WebFetch).url
            sb.append("| [").append(host(url)).append("](").append(url).append(") | ").append(hitsByUrl[url]?.title ?: "Background").append(" |\n")
        }
        return sb.toString()
    }

    private fun extendDoc(ctx: BrainContext, existing: String): String {
        val words = keywords(ctx.task).take(3)
        val heading = words.joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.US) } }.ifEmpty { "Next Steps" }
        val page = pages(ctx).firstOrNull()
        val url = (page?.call as? ToolCall.WebFetch)?.url
        val sb = StringBuilder(existing.trimEnd('\n'))
        sb.append("\n\n## ").append(heading).append("\n\n")
        if (url != null) sb.append(firstSentence(page?.result?.output ?: "") ?: "").append(" See [").append(host(url)).append("](").append(url).append(").\n\n")
        sb.append("- [ ] Review ").append(heading.lowercase(Locale.US)).append(" requirements\n")
        sb.append("- [ ] Add tests for the new flow\n")
        sb.append("- [ ] Roll out behind a setting\n")
        return sb.toString()
    }

    private fun targetDoc(ctx: BrainContext): String? {
        val mentioned = Regex("([\\w./-]+\\.md)").find(ctx.task)?.groupValues?.get(1)
        if (mentioned != null && ctx.workspaceFiles.any { it.endsWith(mentioned) }) return ctx.workspaceFiles.first { it.endsWith(mentioned) }
        return ctx.workspaceFiles.lastOrNull { it.endsWith(".md") }
    }

    private fun cleanupTarget(ctx: BrainContext): String? {
        val tokens = ctx.task.split(Regex("\\s+")).map { it.trim('`', '"', '\'', ',', '.') }
        val names = ctx.workspaceFiles.map { it.removePrefix("/") }
        tokens.firstOrNull { tok -> tok.length > 2 && names.any { it == tok || it.startsWith("$tok/") || it.substringAfterLast('/') == tok } }
            ?.let { tok -> return names.firstOrNull { it == tok || it.substringAfterLast('/') == tok } ?: tok.trimEnd('/') }
        return names.firstOrNull { it.startsWith("tmp/") || it.startsWith("build/") || it.endsWith(".tmp") || it.endsWith(".log") }
            ?.let { if (it.contains('/')) it.substringBefore('/') else it }
    }
}
