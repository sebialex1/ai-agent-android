package com.sebialex.agent.domain.markdown

enum class DiffType { SAME, ADDED, REMOVED }

data class DiffLine(val type: DiffType, val text: String, val oldNo: Int?, val newNo: Int?)

/** LCS line diff. Adequate for documents produced by the agent (bounded to a few thousand lines). */
object LineDiff {
    fun diff(old: String, new: String): List<DiffLine> {
        val a = old.split('\n')
        val b = new.split('\n')
        if (a.size * b.size > 4_000_000) {
            return a.mapIndexed { i, s -> DiffLine(DiffType.REMOVED, s, i + 1, null) } +
                b.mapIndexed { i, s -> DiffLine(DiffType.ADDED, s, null, i + 1) }
        }
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
        val out = mutableListOf<DiffLine>()
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> { out += DiffLine(DiffType.SAME, a[i], i + 1, j + 1); i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> { out += DiffLine(DiffType.REMOVED, a[i], i + 1, null); i++ }
                else -> { out += DiffLine(DiffType.ADDED, b[j], null, j + 1); j++ }
            }
        }
        while (i < a.size) { out += DiffLine(DiffType.REMOVED, a[i], i + 1, null); i++ }
        while (j < b.size) { out += DiffLine(DiffType.ADDED, b[j], null, j + 1); j++ }
        return out
    }

    fun counts(old: String, new: String): Pair<Int, Int> {
        val d = diff(old, new)
        return d.count { it.type == DiffType.ADDED } to d.count { it.type == DiffType.REMOVED }
    }
}
