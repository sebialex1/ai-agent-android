package com.sebialex.agent.data.local

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context

/**
 * Memory-aware ceiling for a local model's context window, applied before the native engine is
 * created. A maxNumTokens the phone cannot hold exhausts native or GPU memory and dies with a
 * native signal no Kotlin catch can stop, so the requested context is capped here instead of
 * relying on exception-based fallback. Calibrated from measured phones (a 2.6 GB model on a
 * 12 GB phone caps near 18K; 10-15K ran there, 20K died). Contexts at or below [FLOOR_TOKENS]
 * are never reduced.
 */
internal object ContextCeiling {

    /** Contexts up to this are considered safe on any phone the app supports. */
    const val FLOOR_TOKENS = 2_048

    private const val RUNTIME_OVERHEAD_BYTES = 1L shl 30

    /** A KV cache still costs at least this per token, for even the tiniest models. */
    private const val MIN_BYTES_PER_TOKEN = 64L shl 10

    /**
     * Per-token cost (KV cache plus GPU buffers that grow with context), scaled from the model's
     * size: every ~20 kB of weights adds about 1 kB of per-token state. Tune with measurements.
     */
    private const val MODEL_BYTES_PER_CONTEXT_TOKEN = 20_000L

    internal fun bytesPerToken(modelBytes: Long): Long =
        maxOf(MIN_BYTES_PER_TOKEN, modelBytes / MODEL_BYTES_PER_CONTEXT_TOKEN)

    /** Largest context a phone with [totalRamBytes] is expected to hold; 0 bytes: unknown, no cap. */
    internal fun deviceLimit(modelBytes: Long, totalRamBytes: Long): Int {
        if (modelBytes <= 0 || totalRamBytes <= 0) return Int.MAX_VALUE
        // Half of RAM stays with the OS, other apps and the app's own heap.
        val budget = totalRamBytes / 2 - modelBytes - RUNTIME_OVERHEAD_BYTES
        if (budget <= 0) return FLOOR_TOKENS
        val tokens = (budget / bytesPerToken(modelBytes)).toInt()
        return maxOf(FLOOR_TOKENS, roundDown(tokens))
    }

    /** The effective context: never above what the phone plausibly holds, and never raised. */
    internal fun effective(requestedTokens: Int, modelBytes: Long, totalRamBytes: Long): Int =
        minOf(requestedTokens, deviceLimit(modelBytes, totalRamBytes))

    private fun roundDown(tokens: Int): Int = tokens / 1_024 * 1_024
}

/** Total RAM in bytes as the system reports it, 0 when it cannot be read. */
internal fun totalRamBytes(context: Context): Long = runCatching {
    val am = context.getSystemService(ActivityManager::class.java) ?: return 0L
    val info = ActivityManager.MemoryInfo()
    am.getMemoryInfo(info)
    info.totalMem
}.getOrDefault(0L)

/**
 * Timestamps of this app's recent process deaths by crash (ApplicationExitInfo), oldest first,
 * empty when the history is unavailable. Low-memory kills are not crashes: the context ceiling
 * deals with those.
 */
internal fun recentCrashes(context: Context, limit: Int = 5): List<Long> = runCatching {
    val am = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
    am.getHistoricalProcessExitReasons(context.packageName, 0, limit)
        .filter { it.reason == ApplicationExitInfo.REASON_CRASH || it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE }
        .map { it.timestamp }
}.getOrDefault(emptyList())

/**
 * Pure decision behind the engine's GPU crash memo. A marker file records the span in which a
 * local model was loaded or answering; a crash timestamp inside that span means the native
 * runtime killed the process mid-model (no stack trace ever reaches the classifier), so the GPU
 * is skipped until the app restarts.
 */
internal object GpuCrashMemo {

    fun shouldSkipGpu(crashTimestamps: List<Long>, markerLastModified: Long): Boolean =
        crashTimestamps.any { it >= markerLastModified }
}
