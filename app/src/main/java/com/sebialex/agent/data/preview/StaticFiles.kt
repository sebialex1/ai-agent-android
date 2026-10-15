package com.sebialex.agent.data.preview

import java.io.File

/**
 * Which file a preview request means, kept pure so the rules can be tested: the URL path is
 * decoded once, query and fragment dropped, folders resolve to their index.html, and anything
 * that ends up outside the served folder (`..`, encoded `%2e%2e`, absolute tricks, a symlink
 * pointing out) is refused. Nothing here touches the network.
 */
object StaticFiles {
    sealed interface Resolution {
        data class Found(val file: File) : Resolution
        /** A folder without the trailing slash: redirect so relative links inside it work. */
        data class Redirect(val location: String) : Resolution
        data object NotFound : Resolution
        data object Forbidden : Resolution
    }

    fun resolve(root: File, rawPath: String): Resolution {
        val base = root.canonicalFile
        val pathOnly = rawPath.substringBefore('#').substringBefore('?')
        val decoded = decode(pathOnly) ?: return Resolution.Forbidden
        if ('\u0000' in decoded || '\\' in decoded) return Resolution.Forbidden
        val segments = decoded.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) return Resolution.Forbidden
        val target = File(base, segments.joinToString(File.separator)).canonicalFile
        if (target != base && !target.path.startsWith(base.path + File.separator)) return Resolution.Forbidden
        return when {
            target.isDirectory -> {
                if (!pathOnly.endsWith("/")) Resolution.Redirect("$pathOnly/")
                else File(target, "index.html").takeIf { it.isFile }?.let { Resolution.Found(it) } ?: Resolution.NotFound
            }
            target.isFile -> Resolution.Found(target)
            else -> Resolution.NotFound
        }
    }

    /** Percent-decoding of a URL path (a `+` stays a plus); null when malformed. */
    internal fun decode(path: String): String? {
        if ('%' !in path) return path
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < path.length) {
            val c = path[i]
            if (c == '%') {
                if (i + 2 >= path.length) return null
                val b = path.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                bytes.write(b)
                i += 3
            } else {
                bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    /** Content type by extension; text types say UTF-8. Unknown files are plain bytes. */
    fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js", "mjs", "cjs" -> "text/javascript; charset=utf-8"
        "json", "map" -> "application/json; charset=utf-8"
        "webmanifest" -> "application/manifest+json; charset=utf-8"
        "txt", "md" -> "text/plain; charset=utf-8"
        "csv" -> "text/csv; charset=utf-8"
        "xml" -> "application/xml; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "avif" -> "image/avif"
        "ico" -> "image/x-icon"
        "bmp" -> "image/bmp"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "wasm" -> "application/wasm"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg", "oga" -> "audio/ogg"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }
}
