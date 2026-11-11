package com.sebialex.agent.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import java.io.File

/**
 * The custom chat background image, copied into the app's own storage on pick (a photo-picker
 * grant can lapse; a private copy never does). One image for all conversations.
 */
object CustomBackground {
    fun file(context: Context): File = File(context.filesDir, "customization/background.jpg")

    /** Copies [uri] into the app's storage; false when it cannot be read as an image. */
    fun save(context: Context, uri: Uri): Boolean = runCatching {
        val target = file(context)
        target.parentFile?.mkdirs()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return false
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        // Keep the decoded bitmap around screen size: a wallpaper is drawn scaled anyway.
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1080) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return false
        target.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out) }
        bitmap.recycle()
        true
    }.getOrDefault(false)

    fun clear(context: Context) {
        file(context).delete()
    }
}

/**
 * The chat screens' background: the custom image (cover-cropped, with a dark scrim at [dim]
 * percent for readability) when one is set, otherwise the theme's gradient.
 */
@Composable
fun Modifier.chatBackground(enabled: Boolean, dim: Int): Modifier {
    if (!enabled) return this.background(Palette.backgroundBrush())
    val context = LocalContext.current
    val bitmap = remember(enabled) {
        val f = CustomBackground.file(context)
        if (f.isFile) BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() else null
    } ?: return this.background(Palette.backgroundBrush())
    return this.drawBehind {
        val scale = maxOf(size.width / bitmap.width, size.height / bitmap.height)
        val w = (bitmap.width * scale).toInt()
        val h = (bitmap.height * scale).toInt()
        withTransform({ translate((size.width - w) / 2f, (size.height - h) / 2f) }) {
            drawImage(bitmap, dstSize = IntSize(w, h), dstOffset = IntOffset.Zero)
        }
        drawRect(Color.Black.copy(alpha = dim / 100f))
    }
}
