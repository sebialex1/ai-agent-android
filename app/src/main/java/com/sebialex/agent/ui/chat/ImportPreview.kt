package com.sebialex.agent.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.sebialex.agent.R
import com.sebialex.agent.ui.components.AppIcon
import com.sebialex.agent.ui.components.FadingText
import com.sebialex.agent.ui.components.QuietIconButton
import com.sebialex.agent.ui.files.FileKind
import com.sebialex.agent.ui.theme.Palette
import com.sebialex.agent.ui.theme.softSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What a preview needs to lay out without jumping: the picture's aspect ratio and, for video,
 * a decoded first frame and the duration. Images are drawn by Coil from the file itself.
 */
private class Thumb(val ratio: Float, val frame: ImageBitmap? = null, val durationMs: Long = 0)

/** Small in-memory cache, so scrolling back to a message never decodes its video frame again. */
private val thumbs = LruCache<String, Thumb>(32)

private fun thumbKey(file: File) = file.path + ":" + file.lastModified()

/** Reads the size of an image (EXIF rotation applied) or the first frame of a video, off the main thread. */
private suspend fun loadThumb(file: File, kind: FileKind): Thumb? = withContext(Dispatchers.IO) {
    thumbs.get(thumbKey(file))?.let { return@withContext it }
    val thumb = runCatching {
        when (kind) {
            FileKind.IMAGE -> {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, o)
                if (o.outWidth <= 0 || o.outHeight <= 0) return@runCatching null
                val rotation = runCatching {
                    android.media.ExifInterface(file.path).getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL,
                    )
                }.getOrDefault(android.media.ExifInterface.ORIENTATION_NORMAL)
                val sideways = rotation == android.media.ExifInterface.ORIENTATION_ROTATE_90 ||
                    rotation == android.media.ExifInterface.ORIENTATION_ROTATE_270 ||
                    rotation == android.media.ExifInterface.ORIENTATION_TRANSPOSE ||
                    rotation == android.media.ExifInterface.ORIENTATION_TRANSVERSE
                Thumb(if (sideways) o.outHeight.toFloat() / o.outWidth else o.outWidth.toFloat() / o.outHeight)
            }
            FileKind.VIDEO -> {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(file.path)
                    val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    val frame = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { scaled(it, 720) }
                    if (frame == null) {
                        Thumb(16f / 9f, null, duration)
                    } else {
                        Thumb(frame.width.toFloat() / frame.height, frame.asImageBitmap(), duration)
                    }
                } finally {
                    runCatching { r.release() }
                }
            }
            else -> null
        }
    }.getOrNull()
    thumb?.also { thumbs.put(thumbKey(file), it) }
}

/** Keeps decoded frames small; a chat bubble never needs more than ~720 px. */
private fun scaled(b: Bitmap, max: Int): Bitmap {
    val longest = maxOf(b.width, b.height)
    if (longest <= max) return b
    val f = max.toFloat() / longest
    return Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true)
        .also { if (it !== b) b.recycle() }
}

/**
 * An imported file inside the user's message: images and videos as a rounded, aspect-fit
 * preview (video shows its first frame, a play badge and its length), anything else as a
 * compact file chip. Tapping opens it in the file viewer (with the built-in player for video).
 */
@Composable
internal fun ImportCard(path: String, file: File, onClick: () -> Unit) {
    val kind = FileKind.of(file.name)
    if (kind != FileKind.IMAGE && kind != FileKind.VIDEO) {
        ImportChip(file, kind, onClick)
        return
    }
    val thumb by produceState<Thumb?>(thumbs.get(thumbKey(file)), file.path, file.lastModified()) {
        if (value == null) value = loadThumb(file, kind)
    }
    // Landscape fills the width, portrait is capped by height; extreme shapes are cropped a little.
    val ratio = (thumb?.ratio ?: 4f / 3f).coerceIn(0.56f, 2.1f)
    val maxW = 248.dp
    val maxH = 300.dp
    val width: Dp = if (maxW / ratio > maxH) maxH * ratio else maxW
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier
            .width(width)
            .aspectRatio(ratio)
            .softSurface(shape, Palette.SurfaceHigh, elevation = 3.dp)
            .clip(shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            kind == FileKind.IMAGE -> {
                val context = LocalContext.current
                AsyncImage(
                    model = ImageRequest.Builder(context).data(file).crossfade(true).build(),
                    contentDescription = file.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            thumb?.frame != null -> Image(
                bitmap = thumb!!.frame!!,
                contentDescription = file.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            else -> AppIcon(R.drawable.ic_play, null, tint = Palette.IconMuted, size = 28.dp)
        }
        if (kind == FileKind.VIDEO) {
            Box(
                Modifier.size(46.dp).clip(CircleShape).background(Color(0x8C000000)),
                contentAlignment = Alignment.Center,
            ) {
                AppIcon(R.drawable.ic_play, "Play video", tint = Palette.IconActive, size = 22.dp)
            }
            val ms = thumb?.durationMs ?: 0L
            if (ms > 0) Text(
                formatDuration(ms),
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextPrimary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0x8C000000))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
    }
}

/** Audio, documents and everything else: icon, name and size. */
@Composable
private fun ImportChip(file: File, kind: FileKind, onClick: () -> Unit) {
    Row(
        Modifier
            .widthIn(max = 280.dp)
            .softSurface(RoundedCornerShape(18.dp), Palette.SurfaceHigh, elevation = 3.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 16.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(if (kind == FileKind.AUDIO) R.drawable.ic_audio else R.drawable.ic_file, null, tint = Palette.IconActive, size = 20.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f, fill = false)) {
            FadingText(file.name, MaterialTheme.typography.titleSmall, Palette.TextPrimary)
            Text(formatBytes(file.length()), style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary)
        }
    }
}

/**
 * Imports waiting to be sent, as a row of small rounded thumbnails above the input, each with
 * a remove button. Sending puts them into the message, where they show as [ImportCard]s.
 */
@Composable
internal fun PendingImports(
    items: List<Pair<String, File>>,
    onRemove: (String) -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { (path, file) -> PendingTile(file, onRemove = { onRemove(path) }, onOpen = { onOpen(path) }) }
    }
}

@Composable
private fun PendingTile(file: File, onRemove: () -> Unit, onOpen: () -> Unit) {
    val kind = FileKind.of(file.name)
    val shape = RoundedCornerShape(16.dp)
    val thumb by produceState<Thumb?>(thumbs.get(thumbKey(file)), file.path, file.lastModified()) {
        if (value == null && kind == FileKind.VIDEO) value = loadThumb(file, kind)
    }
    Box(Modifier.size(68.dp)) {
        Box(
            Modifier
                .fillMaxSize()
                .softSurface(shape, Palette.SurfaceHigh, elevation = 2.dp)
                .clip(shape)
                .clickable(onClick = onOpen),
            contentAlignment = Alignment.Center,
        ) {
            when {
                kind == FileKind.IMAGE -> AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(file).build(),
                    contentDescription = file.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                kind == FileKind.VIDEO && thumb?.frame != null -> Image(
                    bitmap = thumb!!.frame!!,
                    contentDescription = file.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(6.dp)) {
                    AppIcon(if (kind == FileKind.AUDIO) R.drawable.ic_audio else R.drawable.ic_file, null, tint = Palette.IconMuted, size = 20.dp)
                    Text(
                        file.extension.uppercase().take(5),
                        style = MaterialTheme.typography.labelSmall,
                        color = Palette.TextTertiary,
                        maxLines = 1,
                    )
                }
            }
            if (kind == FileKind.VIDEO) AppIcon(R.drawable.ic_play, null, tint = Palette.IconActive, size = 18.dp)
        }
        Box(
            Modifier.align(Alignment.TopEnd).padding(3.dp).size(22.dp).clip(CircleShape).background(Color(0xB3000000)),
            contentAlignment = Alignment.Center,
        ) {
            QuietIconButton(R.drawable.ic_close, "Remove ${file.name}", onClick = onRemove, size = 22.dp, iconSize = 12.dp, tint = Palette.IconActive)
        }
    }
}

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

private fun formatBytes(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "${n / 1024} KB"
    else -> "%.1f MB".format(n / (1024.0 * 1024.0))
}
