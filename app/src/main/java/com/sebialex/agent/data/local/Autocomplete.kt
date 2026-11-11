package com.sebialex.agent.data.local

import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Word suggestions while typing, from the small on-device model (SmolLM2 135M). It only ever
 * sees the text being typed (never the conversation), answers with at most a few words, and
 * runs on its own tiny engine (CPU, small context) so the main model stays in memory.
 */
class Autocomplete(
    private val runtime: LmRuntime,
    private val file: () -> File?,
    private val cacheDir: File,
) {
    private val mutex = Mutex()
    private var engine: LmEngine? = null
    private var loadedPath: String? = null

    /** The continuation for [text], at most [MAX_WORDS] words; null when nothing useful came out. */
    suspend fun suggest(text: String): String? = withContext(Dispatchers.IO) {
        val model = file() ?: return@withContext null
        mutex.withLock {
            val e = runCatching { ensure(model) }.getOrNull() ?: return@withLock null
            val conversation = runCatching {
                e.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(SYSTEM),
                        samplerConfig = SamplerConfig(20, 0.3, 0.95, 0),
                        maxOutputToken = 24,
                    ),
                )
            }.getOrNull() ?: return@withLock null
            try {
                clean(generate(conversation, text))
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { runCatching { conversation.close() } }
            }
        }
    }

    private fun ensure(model: File): LmEngine {
        if (engine != null && loadedPath == model.absolutePath) return engine!!
        engine?.let { runCatching { it.close() } }
        engine = null
        loadedPath = null
        val e = runtime.openEngine(model.absolutePath, gpu = false, contextTokens = 1024, speculative = false, cacheDir.absolutePath)
        engine = e
        loadedPath = model.absolutePath
        return e
    }

    /** Sends [text] and collects the streamed answer; a hang or an error yields an empty string. */
    private suspend fun generate(conversation: LmConversation, text: String): String {
        val out = StringBuilder()
        val done = CompletableDeferred<Unit>()
        conversation.sendMessageAsync(
            Message.user(text),
            object : MessageCallback {
                override fun onMessage(message: Message) {
                    message.contents.contents.forEach { if (it is Content.Text) out.append(it.text) }
                }

                override fun onDone() {
                    done.complete(Unit)
                }

                override fun onError(throwable: Throwable) {
                    done.completeExceptionally(throwable)
                }
            },
            emptyMap(),
        )
        try {
            withTimeoutOrNull(GEN_TIMEOUT_MS) { done.await() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            runCatching { conversation.cancelProcess() }
            throw e
        } catch (_: Throwable) {
        }
        return out.toString()
    }

    /** Keeps at most [MAX_WORDS] plain words: no quotes, no list markers, no lead-in phrases. */
    private fun clean(reply: String): String? {
        var s = reply.replace('\n', ' ').trim()
        s = s.trim('"', '\'', '`', '*', '-', ' ')
        if (s.startsWith(":")) s = s.drop(1).trim()
        if (s.isEmpty()) return null
        val joined = s.split(Regex("\\s+")).take(MAX_WORDS).joinToString(" ").trimEnd('.', '!', '?', ',', ':')
        if (joined.isEmpty() || joined.length > 40) return null
        if (REFUSALS.any { joined.lowercase().startsWith(it) }) return null
        return joined
    }

    companion object {
        /** The tiny catalog model the suggestions come from. */
        const val MODEL_ID = LocalModels.PREFIX + "smollm2-135m-instruct"
        const val MAX_WORDS = 5
        private const val GEN_TIMEOUT_MS = 2_500L
        private val SYSTEM = "You are a typing autocomplete. Continue the user's text with 1 to 5 " +
            "words that naturally follow. Reply with only those words: no quotes, no explanation."
        private val REFUSALS = listOf("i ", "sorry", "here", "sure", "as an ai")
    }
}
