package com.sebialex.agent.domain.tools

import com.sebialex.agent.data.workspace.Workspace
import com.sebialex.agent.domain.model.FileAttachment
import com.sebialex.agent.domain.model.ToolCall
import com.sebialex.agent.domain.model.ToolResult
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A picture made by an image model: its bytes and MIME type (image/png, image/jpeg, ...). */
class GeneratedImage(val bytes: ByteArray, val mime: String)

/** Text-to-image backend for the generate_image tool (the image model picked in Settings > Models). */
fun interface ImageGenerator {
    suspend fun generate(prompt: String, aspectRatio: String?): GeneratedImage
}

/** copy_file, file_info, zip_files, unzip_file and generate_image, all inside the workspace. */
internal object ExtraTools {

    fun copy(ws: Workspace, call: ToolCall.CopyFile): ToolResult {
        val src = ws.resolve(call.from)
        if (!src.exists()) return ToolResult(call.callId, false, "${call.from}: no such file or folder")
        ws.copy(call.from, call.to)
        return ToolResult(call.callId, true, "copied ${ws.toVirtual(call.from)} to ${ws.toVirtual(call.to)}")
    }

    /** Size, type, dates; image dimensions, media duration and resolution, text line count. */
    fun info(ws: Workspace, call: ToolCall.FileInfo): ToolResult {
        val f = ws.resolve(call.path)
        if (!f.exists()) return ToolResult(call.callId, false, "${call.path}: no such file or folder")
        val out = StringBuilder()
        out.append("path: ").append(ws.virtualOf(f)).append('\n')
        if (f.isDirectory) {
            val files = f.walkTopDown().filter { it.isFile }.toList()
            out.append("type: folder\nfiles: ").append(files.size).append("\nsize: ").append(human(files.sumOf { it.length() }))
            return ToolResult(call.callId, true, out.toString())
        }
        val ext = f.extension.lowercase()
        out.append("size: ").append(human(f.length())).append(" (").append(f.length()).append(" bytes)\n")
        out.append("type: ").append(mimeOf(ext)).append('\n')
        out.append("modified: ").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(f.lastModified()))).append('\n')
        when {
            ext in IMAGES -> {
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(f.path, o)
                if (o.outWidth > 0) out.append("dimensions: ").append(o.outWidth).append('x').append(o.outHeight).append('\n')
            }
            ext in MEDIA -> runCatching {
                val r = android.media.MediaMetadataRetriever()
                try {
                    r.setDataSource(f.path)
                    r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let {
                        out.append("duration: ").append(String.format(java.util.Locale.US, "%.1f s", it / 1000.0)).append('\n')
                    }
                    val w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    val h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    if (w != null && h != null) out.append("resolution: ").append(w).append('x').append(h).append('\n')
                    r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)?.let { out.append("title: ").append(it).append('\n') }
                } finally {
                    runCatching { r.release() }
                }
            }
            f.length() < 2_000_000 && !binary(f) -> {
                val text = f.readText()
                out.append("lines: ").append(if (text.isEmpty()) 0 else text.trimEnd('\n').count { it == '\n' } + 1).append('\n')
            }
        }
        return ToolResult(call.callId, true, out.toString().trimEnd())
    }

    fun zip(ws: Workspace, call: ToolCall.ZipFiles): ToolResult {
        val target = ws.resolve(call.output.let { if (it.endsWith(".zip", true)) it else "$it.zip" })
        val sources = call.paths.map { ws.resolve(it) }
        sources.firstOrNull { !it.exists() }?.let { return ToolResult(call.callId, false, "${ws.virtualOf(it)}: no such file or folder") }
        target.parentFile?.mkdirs()
        var count = 0
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            for (src in sources) {
                val base = src.parentFile ?: ws.root
                src.walkTopDown().filter { it.isFile && it.canonicalPath != target.canonicalPath }.forEach { file ->
                    zip.putNextEntry(ZipEntry(file.relativeTo(base).path.replace(File.separatorChar, '/')))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    count++
                }
            }
        }
        val path = ws.virtualOf(target)
        return ToolResult(
            call.callId, true, "zipped $count file${if (count == 1) "" else "s"} into $path (${human(target.length())})",
            attachment = FileAttachment(path, target.name, updatedAt = System.currentTimeMillis()),
        )
    }

    /** Extracts inside the workspace only: an entry that would land outside it is refused. */
    fun unzip(ws: Workspace, call: ToolCall.UnzipFile): ToolResult {
        val archive = ws.resolve(call.path)
        if (!archive.isFile) return ToolResult(call.callId, false, "${call.path}: no such file")
        val dest = ws.resolve(call.to ?: (ws.virtualOf(archive).substringBeforeLast('.')))
        dest.mkdirs()
        val destRoot = dest.canonicalPath + File.separator
        var count = 0
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val out = File(dest, entry.name).canonicalFile
                val inside = out.path == dest.canonicalPath || out.path.startsWith(destRoot)
                if (!inside) {
                    return ToolResult(call.callId, false, "refused: ${entry.name} would be written outside the target folder")
                }
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                    count++
                }
            }
        }
        return ToolResult(call.callId, true, "extracted $count file${if (count == 1) "" else "s"} into ${ws.virtualOf(dest)}")
    }

    /** Asks the image model for a picture and saves it; the file is attached to the answer as an artifact. */
    suspend fun image(ws: Workspace, generator: ImageGenerator?, call: ToolCall.GenerateImage): ToolResult {
        val gen = generator ?: return ToolResult(call.callId, false, "Image generation is not available: pick an image model in Settings > Models > Image generation.")
        val img = gen.generate(call.prompt, call.aspectRatio)
        val ext = when {
            "jpeg" in img.mime || "jpg" in img.mime -> "jpg"
            "webp" in img.mime -> "webp"
            "svg" in img.mime -> "svg"
            else -> "png"
        }
        val wanted = call.filename?.trim()?.takeIf { it.isNotEmpty() }?.let { if ('.' in it.substringAfterLast('/')) it else "$it.$ext" }
            ?: ("images/" + call.prompt.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim().split(' ').take(6).joinToString(" ")
                .replaceFirstChar { it.uppercase() }.ifEmpty { "Image" } + ".$ext")
        val stem = wanted.substringBeforeLast('.')
        val suffix = wanted.substringAfterLast('.')
        var path = wanted
        var n = 2
        // Never overwrite an earlier picture: "Name (2).png", "Name (3).png", ...
        while (ws.exists(path)) path = "$stem (${n++}).$suffix"
        val saved = ws.writeBytes(path, img.bytes)
        val f = ws.resolve(saved)
        val a = FileAttachment(path = ws.virtualOf(f), name = f.name, updatedAt = System.currentTimeMillis())
        return ToolResult(call.callId, true, "saved the generated image to ${a.path} (${human(f.length())}); it is attached to your answer", artifacts = listOf(a))
    }

    private val IMAGES = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif")
    private val MEDIA = setOf("mp4", "webm", "mkv", "mov", "m4v", "3gp", "mp3", "m4a", "ogg", "wav", "flac", "aac", "opus")

    private fun mimeOf(ext: String): String =
        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: if (ext.isEmpty()) "unknown" else "$ext file"

    private fun binary(f: File): Boolean = f.inputStream().use { input ->
        val buf = ByteArray(4096)
        val n = input.read(buf)
        n > 0 && (0 until n).any { buf[it] == 0.toByte() }
    }

    fun human(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", n / 1024.0)
        n < 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", n / (1024.0 * 1024))
        else -> String.format(java.util.Locale.US, "%.2f GB", n / (1024.0 * 1024 * 1024))
    }
}
