package com.sebialex.agent.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sebialex.agent.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** Loads real site favicons with memory + disk caching. Falls back to a monochrome globe vector. */
object FaviconLoader {
    private val memory = LruCache<String, ImageBitmap>(64)
    private val failed = ConcurrentHashMap.newKeySet<String>()

    fun peek(host: String): ImageBitmap? = memory.get(host)

    suspend fun load(context: Context, host: String): ImageBitmap? {
        memory.get(host)?.let { return it }
        if (host.isBlank() || host in failed) return null
        return withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "favicons").apply { mkdirs() }
            val file = File(dir, host.replace(Regex("[^a-zA-Z0-9.-]"), "_") + ".png")
            val bytes = if (file.exists() && file.length() > 0) file.readBytes() else download(host)?.also { file.writeBytes(it) }
            val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.asImageBitmap()
            if (bmp != null) memory.put(host, bmp) else failed += host
            bmp
        }
    }

    private fun download(host: String): ByteArray? = runCatching {
        val conn = URL("https://www.google.com/s2/favicons?domain=$host&sz=64").openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 6000
        try {
            if (conn.responseCode in 200..299) conn.inputStream.use { it.readBytes() } else null
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}

@Composable
fun Favicon(host: String, size: Dp = 16.dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState(initialValue = FaviconLoader.peek(host), host) {
        if (value == null) value = FaviconLoader.load(context.applicationContext, host)
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier.size(size).clip(RoundedCornerShape(size / 5)),
        )
    } else {
        AppIcon(R.drawable.ic_globe, null, modifier = modifier, size = size)
    }
}
