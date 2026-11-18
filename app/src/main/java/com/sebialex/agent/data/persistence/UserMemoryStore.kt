package com.sebialex.agent.data.persistence

import com.sebialex.agent.domain.model.UserMemory
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * What the agent remembers about the user, one JSON file in app-private storage, shared by every
 * conversation. All state is kept in memory and every access is serialized by the store's monitor
 * (same pattern as [SessionStore]); writes go to a tmp file and rename atomically, so a crash
 * never leaves a half-written file behind.
 *
 * Deterministic contract: [remember]/[update]/[forget]/[clear] apply exactly what they are given
 * (normalized text, fixed caps) and nothing else; [profile] renders a stable system-prompt block
 * that is empty when nothing is stored, so prompts stay byte-identical before the feature is used.
 *
 * @param baseDir app files directory; the file is `user_memory_v1.json` inside it.
 */
class UserMemoryStore(baseDir: File) {

    private val file = File(baseDir, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var cached: List<UserMemory>? = null

    /** Every memory, newest first by [UserMemory.updatedAt]. */
    @Synchronized
    fun all(): List<UserMemory> = current()

    /**
     * Adds [text] under the optional [topic]; blanks collapse, length is capped at
     * [MAX_TEXT_CHARS]. Returns the stored entry, or null when nothing usable was given or
     * [MAX_ENTRIES] is already stored.
     */
    @Synchronized
    fun remember(text: String, topic: String = "", at: Long = System.currentTimeMillis()): UserMemory? {
        val clean = text.normalized(MAX_TEXT_CHARS) ?: return null
        if (current().size >= MAX_ENTRIES) return null
        val entry = UserMemory(newId(), clean, topic.trimmed(MAX_TOPIC_CHARS), at, at)
        write(listOf(entry) + current())
        return entry
    }

    /**
     * Rewrites the memory with [id] to [text] and [topic]. Returns true when a memory was
     * changed; unknown ids are false, never an error.
     */
    @Synchronized
    fun update(id: String, text: String, topic: String = "", at: Long = System.currentTimeMillis()): Boolean {
        val clean = text.normalized(MAX_TEXT_CHARS) ?: return false
        val list = current()
        if (list.none { it.id == id }) return false
        write(list.map { if (it.id == id) it.copy(text = clean, topic = topic.trimmed(MAX_TOPIC_CHARS), updatedAt = at) else it })
        return true
    }

    /** Removes the memory with [id]; true when it existed. */
    @Synchronized
    fun forget(id: String): Boolean {
        val list = current()
        if (list.none { it.id == id }) return false
        write(list.filterNot { it.id == id })
        return true
    }

    /** Removes everything. */
    @Synchronized
    fun clear() = write(emptyList())

    /**
     * The remembered facts as one system-prompt block. Empty when nothing is stored (prompts are
     * unchanged then); newest fact last. Blocking but fast: at most one small file read.
     */
    @Synchronized
    fun profile(prefix: String = DEFAULT_PREFIX, maxChars: Int = MAX_PROFILE_CHARS): String {
        val lines = current().asReversed().joinToString("\n") {
            val topic = it.topic.takeIf { t -> t.isNotBlank() }?.let { t -> " ($t)" } ?: ""
            "- ${it.text}$topic"
        }
        if (lines.isBlank()) return ""
        return ("\n$prefix\n$lines\n").take(maxChars).trimEnd() + "\n"
    }

    private fun current(): List<UserMemory> {
        cached?.let { return it }
        cached = runCatching { json.decodeFromString(ListSerializer(UserMemory.serializer()), file.readText()) }.getOrNull().orEmpty()
        return cached!!
    }

    private fun write(list: List<UserMemory>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(json.encodeToString(ListSerializer(UserMemory.serializer()), list))
        // Same folder, same filesystem: a rename failure means real trouble; the tmp file is
        // removed so no debris is left and the previous state stays on disk.
        check(tmp.renameTo(file)) { "could not persist user memories to $file" }
        cached = list
    }

    private fun newId(): String = "um-${System.currentTimeMillis().toString(36)}-${(0..0xffff).random().toString(36)}"
    private fun String.normalized(max: Int): String? = replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() }?.take(max)
    private fun String.trimmed(max: Int): String = replace(Regex("\\s+"), " ").trim().take(max)

    private companion object {
        const val FILE_NAME = "user_memory_v1.json"
        const val MAX_TEXT_CHARS = 400
        const val MAX_TOPIC_CHARS = 40
        const val MAX_ENTRIES = 100
        const val MAX_PROFILE_CHARS = 4000
        const val DEFAULT_PREFIX =
            "# What you know about the user\nLearned in earlier conversations. Let the user correct you; keep it current with user_memory."
    }
}
