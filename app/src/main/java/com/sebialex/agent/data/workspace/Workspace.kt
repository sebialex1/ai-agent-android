package com.sebialex.agent.data.workspace

import java.io.File
import java.io.IOException

class WorkspaceException(message: String) : IOException(message)

/**
 * The sandboxed agent workspace. All paths are resolved relative to [root]; the virtual
 * absolute prefix "/workspace" maps to the root. Nothing can escape the root.
 */
class Workspace(baseDir: File) {
    val root: File = File(baseDir, "workspace").apply { mkdirs() }
    private val snapshots: File = File(baseDir, "snapshots").apply { mkdirs() }

    fun resolve(path: String, cwd: String = "/"): File {
        val virtual = toVirtual(path, cwd)
        val file = File(root, virtual.removePrefix("/"))
        val canonical = file.canonicalFile
        if (!canonical.path.startsWith(root.canonicalPath)) throw WorkspaceException("$path: outside workspace")
        return canonical
    }

    /** Normalises any input path to a virtual absolute path like "/notes/plan.md". */
    fun toVirtual(path: String, cwd: String = "/"): String {
        var p = path.trim().trim('"', '\'')
        if (p.startsWith("/workspace")) p = p.removePrefix("/workspace").ifEmpty { "/" }
        val parts = ArrayDeque<String>()
        val start = if (p.startsWith("/")) p else "$cwd/$p"
        for (seg in start.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) throw WorkspaceException("$path: outside workspace") else parts.removeLast()
                else -> parts.addLast(seg)
            }
        }
        return "/" + parts.joinToString("/")
    }

    fun virtualOf(file: File): String = "/" + file.canonicalFile.relativeTo(root.canonicalFile).path.replace(File.separatorChar, '/')

    fun exists(path: String, cwd: String = "/") = resolve(path, cwd).exists()

    fun read(path: String, cwd: String = "/"): String {
        val f = resolve(path, cwd)
        if (!f.exists()) throw WorkspaceException("$path: no such file")
        if (f.isDirectory) throw WorkspaceException("$path: is a directory")
        return f.readText()
    }

    data class WriteResult(val virtualPath: String, val snapshotId: String?, val previous: String?)

    /** Writes a file. If it existed, the previous content is snapshotted so a diff can be shown. */
    fun write(path: String, content: String, cwd: String = "/", append: Boolean = false): WriteResult {
        val f = resolve(path, cwd)
        if (f.isDirectory) throw WorkspaceException("$path: is a directory")
        f.parentFile?.mkdirs()
        var snapshotId: String? = null
        var previous: String? = null
        if (f.exists()) {
            previous = f.readText()
            snapshotId = "s" + System.currentTimeMillis().toString(36) + (previous.hashCode() and 0xffff).toString(16)
            File(snapshots, snapshotId).writeText(previous)
        }
        if (append) f.appendText(content) else f.writeText(content)
        return WriteResult(virtualOf(f), snapshotId, previous)
    }

    fun snapshot(id: String): String? = File(snapshots, id).takeIf { it.exists() && !id.contains('/') }?.readText()

    fun list(path: String = "/", cwd: String = "/"): List<File> {
        val f = resolve(path, cwd)
        if (!f.exists()) throw WorkspaceException("$path: no such file or directory")
        return if (f.isDirectory) f.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList() else listOf(f)
    }

    fun walk(): List<File> = root.walkTopDown().filter { it.isFile }.toList()

    fun mkdir(path: String, cwd: String = "/") { resolve(path, cwd).mkdirs() }

    fun delete(path: String, cwd: String = "/", recursive: Boolean = false) {
        val f = resolve(path, cwd)
        if (f == root.canonicalFile) throw WorkspaceException("refusing to remove workspace root")
        if (!f.exists()) throw WorkspaceException("$path: no such file or directory")
        if (f.isDirectory && !recursive) throw WorkspaceException("$path: is a directory")
        if (!f.deleteRecursively()) throw WorkspaceException("$path: could not remove")
    }

    fun move(from: String, to: String, cwd: String = "/") {
        val src = resolve(from, cwd)
        var dst = resolve(to, cwd)
        if (!src.exists()) throw WorkspaceException("$from: no such file or directory")
        if (dst.isDirectory) dst = File(dst, src.name)
        dst.parentFile?.mkdirs()
        if (!src.renameTo(dst)) throw WorkspaceException("cannot move $from")
    }

    fun copy(from: String, to: String, cwd: String = "/") {
        val src = resolve(from, cwd)
        var dst = resolve(to, cwd)
        if (!src.exists()) throw WorkspaceException("$from: no such file or directory")
        if (dst.isDirectory) dst = File(dst, src.name)
        dst.parentFile?.mkdirs()
        src.copyRecursively(dst, overwrite = true)
    }
}
