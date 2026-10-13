package com.sebialex.agent.data.workspace

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Copies workspace files out to the phone's public Downloads folder, under
 * `Download/AgentForAndroid/`. Android 10+ goes through MediaStore (no storage permission
 * needed); older versions write the file directly. Folders are zipped first.
 */
object Downloads {
    const val SUBDIR = "AgentForAndroid"

    /** Saves [file] (or a zip of a folder) and returns the visible path, e.g. "Download/AgentForAndroid/report.md". */
    fun save(context: Context, file: File): String {
        if (!file.exists()) throw IOException("${file.name}: no such file")
        if (file.isDirectory) {
            val zip = File(context.cacheDir, "${file.name.ifEmpty { "workspace" }}.zip")
            try {
                zipFolder(file, zip)
                return save(context, zip, zip.name)
            } finally {
                zip.delete()
            }
        }
        return save(context, file, file.name)
    }

    private fun save(context: Context, file: File, name: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(name))
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + SUBDIR)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Downloads is not available")
            try {
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out, 64 * 1024) } }
                    ?: throw IOException("could not write to Downloads")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
            return "${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR/$name"
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SUBDIR)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("storage permission is needed to write to Downloads")
        val base = name.substringBeforeLast('.')
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var target = File(dir, name)
        var n = 1
        while (target.exists()) target = File(dir, "$base (${n++})$ext")
        file.copyTo(target)
        return "${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR/${target.name}"
    }

    private fun zipFolder(dir: File, zip: File) {
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                out.putNextEntry(ZipEntry(f.relativeTo(dir).path.replace(File.separatorChar, '/')))
                f.inputStream().use { it.copyTo(out, 64 * 1024) }
                out.closeEntry()
            }
        }
    }

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
            "md", "markdown" -> "text/markdown"
            "kt", "py", "sh", "json", "log", "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
    }
}
