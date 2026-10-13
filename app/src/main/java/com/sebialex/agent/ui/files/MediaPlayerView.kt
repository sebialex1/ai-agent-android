package com.sebialex.agent.ui.files

import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sebialex.agent.R
import com.sebialex.agent.ui.components.AppIcon
import com.sebialex.agent.ui.theme.MonoSmall
import com.sebialex.agent.ui.theme.Palette
import com.sebialex.agent.ui.theme.softSurface
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

/**
 * A deliberately small player on top of the platform [MediaPlayer] (no ExoPlayer/Media3):
 * play/pause, a seek slider and elapsed/total time. Video renders into a [TextureView] so it
 * clips and fades with the rest of Compose. Playback pauses when the app goes to the background
 * and the player is released when the screen closes.
 */
@Stable
private class PlayerState(val player: MediaPlayer) {
    var prepared by mutableStateOf(false)
    var playing by mutableStateOf(false)
    var ended by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var position by mutableIntStateOf(0)
    var duration by mutableIntStateOf(0)
    var videoWidth by mutableIntStateOf(0)
    var videoHeight by mutableIntStateOf(0)
    /** While the slider is dragged, its value wins over the polled position. */
    var scrub by mutableStateOf<Float?>(null)
    var released = false

    fun toggle() {
        if (!prepared || failed || released) return
        runCatching {
            if (player.isPlaying) {
                player.pause()
                playing = false
                position = player.currentPosition
            } else {
                if (ended) { player.seekTo(0); position = 0; ended = false }
                player.start()
                playing = true
            }
        }
    }

    fun pause() {
        if (released) return
        runCatching { if (player.isPlaying) { player.pause(); position = player.currentPosition } }
        playing = false
    }

    fun seekTo(ms: Int) {
        if (!prepared || released) return
        runCatching { player.seekTo(ms.toLong(), MediaPlayer.SEEK_CLOSEST) }
        position = ms
        ended = false
    }
}

@Composable
private fun rememberPlayer(file: File, video: Boolean): PlayerState {
    val state = remember(file) { PlayerState(MediaPlayer()) }
    DisposableEffect(state) {
        val p = state.player
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(if (video) AudioAttributes.CONTENT_TYPE_MOVIE else AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            p.setOnPreparedListener {
                state.duration = it.duration.coerceAtLeast(0)
                state.prepared = true
                // Draws the first frame so a paused video is not a black box.
                if (video) runCatching { it.seekTo(0) }
            }
            p.setOnVideoSizeChangedListener { _, w, h -> state.videoWidth = w; state.videoHeight = h }
            p.setOnCompletionListener {
                state.playing = false
                state.ended = true
                state.position = state.duration
            }
            p.setOnErrorListener { _, _, _ ->
                state.failed = true
                state.playing = false
                true
            }
            p.setDataSource(file.path)
            p.prepareAsync()
        } catch (e: Exception) {
            state.failed = true
        }
        onDispose {
            state.released = true
            runCatching { p.stop() }
            p.release()
        }
    }
    // Poll the position only while playing; MediaPlayer has no position callback.
    LaunchedEffect(state, state.playing) {
        while (state.playing && !state.released) {
            if (state.scrub == null) state.position = runCatching { state.player.currentPosition }.getOrDefault(state.position)
            delay(200)
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, state) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) state.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return state
}

@Composable
internal fun VideoPreview(file: File, modifier: Modifier = Modifier) {
    val state = rememberPlayer(file, video = true)
    Column(modifier.fillMaxSize()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
                // Tap anywhere on the picture to play or pause.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { state.toggle() },
            contentAlignment = Alignment.Center,
        ) {
            val ratio = if (state.videoWidth > 0 && state.videoHeight > 0) state.videoWidth.toFloat() / state.videoHeight else 16f / 9f
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            private var surface: Surface? = null
                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                val s = Surface(st)
                                surface = s
                                if (!state.released) runCatching { state.player.setSurface(s) }
                            }
                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                if (!state.released) runCatching { state.player.setSurface(null) }
                                surface?.release()
                                surface = null
                                return true
                            }
                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                        }
                    }
                },
                update = { it.keepScreenOn = state.playing },
                // Fits inside the box either way: landscape fills the width, portrait the height.
                modifier = Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 1f),
            )
            when {
                state.failed -> Message("This video cannot be played on this device")
                state.prepared && !state.playing -> BigPlayButton(onClick = state::toggle)
            }
        }
        Controls(state, Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
    }
}

@Composable
internal fun AudioPreview(file: File, modifier: Modifier = Modifier) {
    val state = rememberPlayer(file, video = false)
    Box(modifier.fillMaxSize().padding(horizontal = 22.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(
                Modifier.size(112.dp).softSurface(RoundedCornerShape(32.dp), Palette.SurfaceHigh, elevation = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                AppIcon(R.drawable.ic_audio, null, tint = Palette.IconMuted, size = 44.dp)
            }
            if (state.failed) Message("This audio cannot be played on this device")
            Controls(state, Modifier.fillMaxWidth())
        }
    }
}

/** Play/pause, elapsed time, seek slider, total time, in one soft row. */
@Composable
private fun Controls(state: PlayerState, modifier: Modifier = Modifier) {
    val duration = state.duration
    val shown = state.scrub ?: state.position.toFloat()
    Row(
        modifier
            .fillMaxWidth()
            .softSurface(RoundedCornerShape(20.dp), Palette.Surface, elevation = 3.dp)
            .padding(start = 6.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(enabled = state.prepared && !state.failed, onClick = state::toggle),
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(
                if (state.playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (state.playing) "Pause" else "Play",
                tint = if (state.prepared && !state.failed) Palette.IconActive else Palette.TextTertiary,
                size = 22.dp,
            )
        }
        Text(formatTime(shown.toInt()), style = MonoSmall, color = Palette.TextSecondary, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 40.dp))
        Spacer(Modifier.width(10.dp))
        Slider(
            value = if (duration > 0) (shown / duration).coerceIn(0f, 1f) else 0f,
            onValueChange = { state.scrub = it * duration },
            onValueChangeFinished = {
                state.scrub?.let { state.seekTo(it.toInt()) }
                state.scrub = null
            },
            enabled = state.prepared && !state.failed && duration > 0,
            colors = SliderDefaults.colors(
                thumbColor = Palette.TextPrimary,
                activeTrackColor = Palette.TextPrimary,
                inactiveTrackColor = Palette.SurfaceHighest,
                disabledThumbColor = Palette.SurfaceHighest,
                disabledActiveTrackColor = Palette.SurfaceHighest,
                disabledInactiveTrackColor = Palette.SurfaceHigh,
            ),
            modifier = Modifier.weight(1f).height(36.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(if (duration > 0) formatTime(duration) else "--:--", style = MonoSmall, color = Palette.TextTertiary, modifier = Modifier.widthIn(min = 40.dp))
    }
}

@Composable
private fun BigPlayButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(Color(0x99000000))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(R.drawable.ic_play, "Play", tint = Palette.IconActive, size = 28.dp)
    }
}

@Composable
private fun Message(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.TextTertiary, textAlign = TextAlign.Center)
}

/** 75_000 -> "1:15", 3_725_000 -> "1:02:05". */
private fun formatTime(ms: Int): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}
