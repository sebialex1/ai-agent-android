package com.sebialex.agent.data.persistence

import com.sebialex.agent.domain.agent.AuditSink
import com.sebialex.agent.domain.model.Session
import com.sebialex.agent.domain.model.Step
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Stores the current conversation as JSON in app-private storage. */
class SessionStore(baseDir: File) {
    private val dir = File(baseDir, "sessions").apply { mkdirs() }
    private val current = File(dir, "current.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): Session? = runCatching { json.decodeFromString<Session>(current.readText()) }.getOrNull()

    fun save(session: Session) {
        val tmp = File(dir, "current.json.tmp")
        tmp.writeText(json.encodeToString(session))
        tmp.renameTo(current)
    }

    /** Moves the current conversation out of the way; it is kept on disk for the audit trail. */
    fun archive(session: Session) {
        if (session.turns.isEmpty()) return
        File(dir, "${session.id}.json").writeText(json.encodeToString(session))
    }

    fun clearAll() {
        dir.listFiles()?.forEach { it.delete() }
    }
}

/** Append-only JSON-lines audit log of every executed or attempted action. */
class FileAuditLog(baseDir: File) : AuditSink {
    private val file = File(baseDir, "audit.jsonl")
    private val json = Json { encodeDefaults = true }

    @Synchronized
    override fun record(step: Step) {
        runCatching { file.appendText(json.encodeToString(step) + "\n") }
    }
}
