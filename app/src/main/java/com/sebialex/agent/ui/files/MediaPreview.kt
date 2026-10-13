package com.sebialex.agent.ui.files

import android.os.Build
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import com.sebialex.agent.ui.theme.Palette
import java.io.File

/** What the file viewer shows for a file, picked by extension. Anything unknown is text. */
internal enum class FileKind {
    IMAGE, VIDEO, AUDIO, TEXT;

    companion object {
        private val IMAGES = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
        private val VIDEOS = setOf("mp4", "webm", "mkv", "mov", "m4v")
        private val AUDIO_FILES = setOf("mp3", "ogg", "wav", "m4a")

        fun of(name: String): FileKind = when (name.substringAfterLast('.', "").lowercase()) {
            in IMAGES -> IMAGE
            in VIDEOS -> VIDEO
            in AUDIO_FILES -> AUDIO
            else -> TEXT
        }
    }
}

/** Short human type for the viewer header, e.g. "PNG image", "Markdown", "KT file". */
internal fun typeLabel(name: String, kind: FileKind): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        kind == FileKind.IMAGE -> "${if (ext == "jpg") "JPEG" else ext.uppercase()} image"
        kind == FileKind.VIDEO -> "${ext.uppercase()} video"
        kind == FileKind.AUDIO -> "${ext.uppercase()} audio"
        ext == "md" || ext == "markdown" -> "Markdown"
        ext == "txt" || ext.isEmpty() -> "Text"
        else -> "${ext.uppercase()} file"
    }
}

/**
 * An image from the workspace, fitted to the screen. Pinch to zoom, drag to pan while zoomed,
 * double-tap to toggle 2.5x. GIFs animate. Coil keys local files by their last-modified time,
 * so a file the agent rewrites shows its new content.
 */
@Composable
internal fun ImagePreview(file: File, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val loader = remember(context) {
        ImageLoader.Builder(context)
            .components {
                if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }
    val request = remember(file) { ImageRequest.Builder(context).data(file).build() }
    var scale by remember(file) { mutableFloatStateOf(1f) }
    var offset by remember(file) { mutableStateOf(Offset.Zero) }
    var state by remember(file) { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
    Box(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(file) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                })
            }
            .pointerInput(file) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = request,
            contentDescription = file.name,
            imageLoader = loader,
            contentScale = ContentScale.Fit,
            onState = { state = it },
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
        if (state is AsyncImagePainter.State.Error) {
            Text("Could not display this image", style = MaterialTheme.typography.bodyMedium, color = Palette.TextTertiary)
        }
    }
}
