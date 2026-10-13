package com.sebialex.agent.data.workspace

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.util.Base64
import com.sebialex.agent.domain.model.ImageInput
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Turns workspace images (and a few frames of a video) into [ImageInput]s for vision models:
 * decoded with subsampling, EXIF rotation applied, longest side at most [MAX_SIDE] px, then
 * JPEG (PNG when the picture has transparency). Nothing is written back to the workspace.
 */
object ImageEncoder {
    private const val MAX_SIDE = 1568
    private const val MAX_FILE_BYTES = 60L * 1024 * 1024
    private val VIDEO_EXT = setOf("mp4", "webm", "mkv", "mov", "m4v", "3gp")

    fun isVideo(name: String) = name.substringAfterLast('.', "").lowercase() in VIDEO_EXT

    /** One image file, or null when it cannot be decoded. */
    fun image(file: File, label: String): ImageInput? = runCatching {
        if (!file.isFile || file.length() > MAX_FILE_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        encode(rotated(decoded, file), label)
    }.getOrNull()

    /** Up to [count] frames spread over a video (start, middle, end), each labelled with its time. */
    fun videoFrames(file: File, label: String, count: Int = 3): List<ImageInput> = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.path)
            val durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val times = if (durationMs <= 0 || count <= 1) listOf(0L) else (0 until count).map { i -> durationMs * (2 * i + 1) / (2 * count) }
            times.mapNotNull { t ->
                val frame = r.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return@mapNotNull null
                encode(frame, "$label at ${clock(t)}")
            }
        } finally {
            runCatching { r.release() }
        }
    }.getOrDefault(emptyList())

    /** An image, or frames of a video, from a workspace file. */
    fun load(file: File, label: String): List<ImageInput> =
        if (isVideo(file.name)) videoFrames(file, label) else listOfNotNull(image(file, label))

    private fun encode(src: Bitmap, label: String): ImageInput {
        val longest = maxOf(src.width, src.height)
        val bitmap = if (longest > MAX_SIDE) {
            val f = MAX_SIDE.toFloat() / longest
            Bitmap.createScaledBitmap(src, (src.width * f).toInt().coerceAtLeast(1), (src.height * f).toInt().coerceAtLeast(1), true)
        } else src
        val png = bitmap.hasAlpha()
        val bytes = ByteArrayOutputStream().also {
            bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 85, it)
        }.toByteArray()
        if (bitmap !== src) bitmap.recycle()
        src.recycle()
        return ImageInput(label, if (png) "image/png" else "image/jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP))
    }

    private fun rotated(b: Bitmap, file: File): Bitmap {
        val degrees = runCatching {
            when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (degrees == 0f) return b
        val out = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(degrees) }, true)
        if (out !== b) b.recycle()
        return out
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}
