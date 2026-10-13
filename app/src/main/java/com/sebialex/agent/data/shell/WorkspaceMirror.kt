package com.sebialex.agent.data.shell

import java.io.File

/**
 * Two-way mirror between a conversation's private workspace (`files/conversations/<id>/workspace`,
 * the source of truth that the Workspace screen and the file viewer read) and its shared copy that
 * Termux can reach (`/storage/emulated/0/AgentSandbox/<id>/workspace`).
 *
 * [push] runs before a Termux command and [pull] right after it, so files the agent writes are
 * visible to Termux, and files that apt/pip/python/yt-dlp create appear in the app.
 * A small manifest (size + mtime per file on both sides at the last sync) is used to copy only
 * changed files and to propagate deletions in both directions.
 */
class WorkspaceMirror(
    private val local: File,
    val shared: File,
    private val manifestFile: File,
) {
    private data class Entry(val local: String, val shared: String)

    private val entries = HashMap<String, Entry>().also { load(it) }

    @Synchronized
    fun push() {
        if (!shared.isDirectory && !shared.mkdirs()) {
            throw TermuxException("could not create the shared workspace at ${shared.absolutePath} (permission denied?)")
        }
        val localFiles = files(local)
        for ((rel, f) in localFiles) {
            val target = File(shared, rel)
            val e = entries[rel]
            if (e == null || e.local != stamp(f) || !target.exists()) {
                copy(f, target)
                entries[rel] = Entry(stamp(f), stamp(target))
            }
        }
        // Files the app deleted since the last sync are deleted on the shared side too.
        for (rel in entries.keys.toList()) {
            if (rel !in localFiles) {
                File(shared, rel).delete()
                entries.remove(rel)
            }
        }
        dirs(local).forEach { File(shared, it).mkdirs() }
        save()
    }

    @Synchronized
    fun pull() {
        if (!shared.isDirectory) return
        val sharedFiles = files(shared)
        for ((rel, f) in sharedFiles) {
            val target = File(local, rel)
            val e = entries[rel]
            if (e == null || e.shared != stamp(f) || !target.exists()) {
                copy(f, target)
                entries[rel] = Entry(stamp(target), stamp(f))
            }
        }
        // Files removed inside Termux (rm, mv, pip cleaning up) disappear locally as well.
        for (rel in entries.keys.toList()) {
            if (rel !in sharedFiles) {
                File(local, rel).delete()
                entries.remove(rel)
            }
        }
        val sharedDirs = dirs(shared).toSet()
        sharedDirs.forEach { File(local, it).mkdirs() }
        // Empty local directories that no longer exist in Termux were removed there.
        dirs(local).sortedByDescending { it.length }.forEach { rel ->
            val d = File(local, rel)
            if (rel !in sharedDirs && d.listFiles().isNullOrEmpty()) d.delete()
        }
        save()
    }

    private fun files(root: File): Map<String, File> =
        root.walkTopDown()
            .onEnter { it == root || it.name !in IGNORED }
            .filter { it.isFile }
            .associateBy { it.relativeTo(root).path.replace(File.separatorChar, '/') }

    private fun dirs(root: File): List<String> =
        root.walkTopDown()
            .onEnter { it == root || it.name !in IGNORED }
            .filter { it.isDirectory && it != root }
            .map { it.relativeTo(root).path.replace(File.separatorChar, '/') }
            .toList()

    private fun copy(from: File, to: File) {
        to.parentFile?.mkdirs()
        from.inputStream().use { input -> to.outputStream().use { input.copyTo(it, 64 * 1024) } }
        runCatching { to.setLastModified(from.lastModified()) }
        if (from.canExecute()) runCatching { to.setExecutable(true) }
    }

    private fun stamp(f: File) = "${f.length()}:${f.lastModified()}"

    private fun load(into: HashMap<String, Entry>) {
        if (!manifestFile.exists()) return
        runCatching {
            manifestFile.readLines().forEach { line ->
                val parts = line.split('\t')
                if (parts.size == 3) into[parts[0]] = Entry(parts[1], parts[2])
            }
        }
    }

    private fun save() {
        manifestFile.parentFile?.mkdirs()
        manifestFile.writeText(entries.entries.joinToString("\n") { (k, v) -> "$k\t${v.local}\t${v.shared}" })
    }

    private companion object {
        /** Scratch space that never leaves its side. */
        val IGNORED = setOf(".tmp")
    }
}
