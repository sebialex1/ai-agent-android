package com.sebialex.agent.data.persistence

import com.sebialex.agent.domain.agent.AuditSink
import com.sebialex.agent.domain.model.Session
import com.sebialex.agent.domain.model.SessionSummary
import com.sebialex.agent.domain.model.Step
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Stores every conversation in its own directory under app-private storage:
 *
 * ```
 * files/conversations/<id>/session.json   messages, steps, files, sources
 * files/conversations/<id>/audit.jsonl    raw audit trail for this conversation only
 * files/conversations/<id>/workspace/     this conversation's workspace
 * files/conversations/<id>/snapshots/     previous versions for diffs
 * files/conversations/active              id of the conversation shown on launch
 * ```
 *
 * Nothing is shared between conversations.
 */
class SessionStore(private val baseDir: File) {
    private val root = File(baseDir, "conversations").apply { mkdirs() }
    private val activeFile = File(root, "active")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Called (on the saving thread) after a conversation was saved, deleted or all were cleared; used to schedule backups. */
    @Volatile var onChanged: (() -> Unit)? = null

    /** Last-updated time of every saved conversation, by id. */
    @Synchronized
    fun updatedAtById(): Map<String, Long> = (root.listFiles() ?: emptyArray())
        .filter { it.isDirectory }.mapNotNull { load(it.name) }.associate { it.id to it.updatedAt }

    /** All conversations that have messages, for a backup. Workspace files and audit logs are not included. */
    @Synchronized
    fun loadAll(): List<Session> = (root.listFiles() ?: emptyArray())
        .filter { it.isDirectory }.mapNotNull { load(it.name) }.filter { it.turns.isNotEmpty() }

    fun dirFor(id: String): File {
        require(id.isNotBlank() && !id.contains('/') && !id.contains("..")) { "bad session id" }
        return File(root, id)
    }

    @Synchronized
    fun load(id: String): Session? = runCatching {
        json.decodeFromString<Session>(File(dirFor(id), SESSION_FILE).readText()).let { s ->
            // Answers saved by older builds may carry an echo of the app's action record.
            if (s.turns.none { it.text.contains(com.sebialex.agent.domain.agent.TurnRecord.LEGACY_HEADER, ignoreCase = true) }) s
            else s.copy(turns = s.turns.map(com.sebialex.agent.domain.agent.TurnRecord::clean))
        }
    }.getOrNull()

    @Synchronized
    fun save(session: Session) {
        val dir = dirFor(session.id).apply { mkdirs() }
        val tmp = File(dir, "$SESSION_FILE.tmp")
        tmp.writeText(json.encodeToString(session))
        if (!tmp.renameTo(File(dir, SESSION_FILE))) {
            File(dir, SESSION_FILE).writeText(tmp.readText()); tmp.delete()
        }
        onChanged?.invoke()
    }

    /** Saved conversations with at least one message, most recently updated first. */
    @Synchronized
    fun list(): List<SessionSummary> = (root.listFiles() ?: emptyArray())
        .filter { it.isDirectory }
        .mapNotNull { dir -> load(dir.name) }
        .filter { it.turns.isNotEmpty() }
        .map { SessionSummary(it.id, it.title, it.updatedAt, it.turns.size, it.pinned) }
        .sortedByDescending { it.updatedAt }

    /** Deletes a conversation together with its workspace and audit trail. */
    @Synchronized
    fun delete(id: String) {
        dirFor(id).deleteRecursively()
        if (activeId() == id) activeFile.delete()
        onChanged?.invoke()
    }

    fun activeId(): String? = runCatching { activeFile.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    fun setActive(id: String) {
        runCatching { activeFile.writeText(id) }
    }

    @Synchronized
    fun clearAll() {
        root.listFiles()?.forEach { it.deleteRecursively() }
        onChanged?.invoke()
    }

    /**
     * Earlier builds kept one "current.json" (plus archives) and a single shared workspace.
     * Real conversations are moved into their own directories; the pre-loaded sample ("seed")
     * is dropped. The old shared workspace is left untouched and is not attached to any session.
     */
    @Synchronized
    fun migrateLegacy() {
        val legacy = File(baseDir, "sessions")
        if (!legacy.isDirectory) return
        legacy.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }?.forEach { f ->
            val s = runCatching { json.decodeFromString<Session>(f.readText()) }.getOrNull() ?: return@forEach
            val turns = s.turns.filterNot { it.id.startsWith("seed-") }
            if (s.id != "seed" && turns.isNotEmpty() && load(s.id) == null) {
                val updated = turns.maxOf { it.createdAt }
                save(s.copy(turns = turns, updatedAt = updated))
                // Bring along only the files this conversation actually produced.
                val oldWs = File(baseDir, "workspace")
                val newWs = File(dirFor(s.id), "workspace")
                turns.flatMap { it.attachments }.map { it.path.removePrefix("/") }.distinct().forEach { rel ->
                    val src = File(oldWs, rel)
                    if (src.isFile && src.canonicalPath.startsWith(oldWs.canonicalPath)) {
                        runCatching { src.copyTo(File(newWs, rel), overwrite = false) }
                    }
                }
                if (f.name == "current.json") setActive(s.id)
            }
        }
        legacy.deleteRecursively()
    }

    companion object {
        private const val SESSION_FILE = "session.json"
    }
}

/** Append-only JSON-lines audit log of every executed or attempted action in one conversation. */
class FileAuditLog(dir: File) : AuditSink {
    private val file = File(dir.apply { mkdirs() }, "audit.jsonl")
    private val json = Json { encodeDefaults = true }

    @Synchronized
    override fun record(step: Step) {
        runCatching { file.appendText(json.encodeToString(step) + "\n") }
    }
}
