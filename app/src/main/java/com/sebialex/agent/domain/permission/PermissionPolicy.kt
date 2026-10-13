package com.sebialex.agent.domain.permission

import com.sebialex.agent.domain.model.ToolCall

sealed interface PermissionDecision {
    data object Allow : PermissionDecision
    data class Ask(val reason: RiskReason, val summary: String) : PermissionDecision
    data class Deny(val reason: RiskReason, val summary: String) : PermissionDecision
}

enum class RiskReason(val label: String) {
    DESTRUCTIVE("destructive"),
    PACKAGE_INSTALL("package install"),
    NETWORK("network"),
    OUTSIDE_WORKSPACE("outside workspace"),
    PRIVILEGE("privilege escalation"),
}

/**
 * Evaluates every tool call before it executes. Pure and deterministic so it can be unit tested.
 */
class PermissionPolicy(
    private val askBeforeWebAccess: () -> Boolean = { false },
) {
    private val destructive = setOf("rm", "rmdir", "shred", "truncate", "dd", "mkfs")
    private val installers = listOf(
        "pip install", "pip3 install", "python -m pip install", "python3 -m pip install",
        "pkg install", "pkg upgrade", "pkg reinstall", "apt install", "apt-get install", "apt upgrade", "apt-get upgrade",
        "apt reinstall", "dpkg -i", "npm install", "npm i ", "yarn add", "gem install", "cargo install",
    )
    private val network = setOf("curl", "wget", "ssh", "scp", "nc", "git", "ftp", "rsync")
    private val privilege = setOf("sudo", "su", "chmod", "chown", "mount")

    fun evaluate(call: ToolCall): PermissionDecision = when (call) {
        is ToolCall.Shell -> evaluateShell(call.command)
        is ToolCall.WriteFile -> if (isOutside(call.path)) {
            PermissionDecision.Ask(RiskReason.OUTSIDE_WORKSPACE, call.path)
        } else PermissionDecision.Allow
        is ToolCall.ReadFile -> if (isOutside(call.path)) {
            PermissionDecision.Ask(RiskReason.OUTSIDE_WORKSPACE, call.path)
        } else PermissionDecision.Allow
        is ToolCall.WebSearch -> if (askBeforeWebAccess()) PermissionDecision.Ask(RiskReason.NETWORK, call.query) else PermissionDecision.Allow
        is ToolCall.WebFetch -> if (askBeforeWebAccess()) PermissionDecision.Ask(RiskReason.NETWORK, call.url) else PermissionDecision.Allow
        // Python runs inside the sandbox like any shell command; installing packages asks first.
        is ToolCall.Python -> if (call.packages.isNotEmpty()) {
            PermissionDecision.Ask(RiskReason.PACKAGE_INSTALL, "pip install " + call.packages.joinToString(" "))
        } else if (isOutside(call.scriptPath)) {
            PermissionDecision.Ask(RiskReason.OUTSIDE_WORKSPACE, call.scriptPath)
        } else PermissionDecision.Allow
        is ToolCall.Invalid -> PermissionDecision.Allow
    }

    fun evaluateShell(command: String): PermissionDecision {
        val segments = command.split("&&", "||", ";", "|").map { it.trim() }.filter { it.isNotEmpty() }
        var worst: PermissionDecision = PermissionDecision.Allow
        for (segment in segments) {
            val tokens = segment.split(Regex("\\s+"))
            val head = tokens.first()
            val decision = when {
                head in privilege && head != "chmod" -> PermissionDecision.Deny(RiskReason.PRIVILEGE, segment)
                installers.any { segment.startsWith(it) } -> PermissionDecision.Ask(RiskReason.PACKAGE_INSTALL, segment)
                head in destructive -> PermissionDecision.Ask(RiskReason.DESTRUCTIVE, segment)
                head in network -> PermissionDecision.Ask(RiskReason.NETWORK, segment)
                head == "chmod" -> PermissionDecision.Ask(RiskReason.PRIVILEGE, segment)
                tokens.drop(1).any { isOutside(it) } && writesFiles(head, segment) -> PermissionDecision.Ask(RiskReason.OUTSIDE_WORKSPACE, segment)
                segment.contains(">") && segment.substringAfterLast(">").trim().let { isOutside(it) } ->
                    PermissionDecision.Ask(RiskReason.OUTSIDE_WORKSPACE, segment)
                else -> PermissionDecision.Allow
            }
            worst = moreSevere(worst, decision)
        }
        return worst
    }

    private fun writesFiles(head: String, segment: String) =
        head in setOf("mv", "cp", "touch", "mkdir", "tee", "ln") || segment.contains(">")

    private fun moreSevere(a: PermissionDecision, b: PermissionDecision): PermissionDecision {
        fun rank(d: PermissionDecision) = when (d) {
            PermissionDecision.Allow -> 0
            is PermissionDecision.Ask -> 1
            is PermissionDecision.Deny -> 2
        }
        return if (rank(b) > rank(a)) b else a
    }

    companion object {
        fun isOutside(path: String): Boolean {
            val p = path.trim().trim('"', '\'')
            if (p.isEmpty() || p.startsWith("-")) return false
            if (p.startsWith("/") && !p.startsWith("/workspace")) return true
            if (p.startsWith("~")) return true
            var depth = 0
            for (part in p.split('/')) {
                when (part) {
                    "", "." -> Unit
                    ".." -> { depth--; if (depth < 0) return true }
                    else -> depth++
                }
            }
            return false
        }
    }
}
