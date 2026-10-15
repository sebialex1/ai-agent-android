package com.sebialex.agent.ui.preview

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.sebialex.agent.R
import com.sebialex.agent.data.preview.PreviewLifecycle
import com.sebialex.agent.data.preview.PreviewServer
import com.sebialex.agent.domain.model.WebPreview
import com.sebialex.agent.ui.components.QuietIconButton
import com.sebialex.agent.ui.theme.Palette
import com.sebialex.agent.ui.theme.softSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The preview window of a web app the agent built: a near-full-screen sheet with a title bar
 * (name, folder, whether it is live, reload, close) over a WebView of the app.
 *
 * The app is served from its workspace folder by a [PreviewServer] on 127.0.0.1, which runs
 * only while this window is focused: it starts when the window opens (or is focused again),
 * keeps running through short trips away, stops after [PreviewLifecycle.IDLE_MS] out of focus,
 * and always stops when the window closes. The page keeps its address across a pause when the
 * port is still free. The agent can rebuild files meanwhile; reload shows them (nothing is cached).
 *
 * The WebView is locked down: JavaScript and DOM storage for the app, but no file or content
 * access, no file-URL cross access, no mixed content, no geolocation, no popups and no bridge
 * to the app; links leaving the local server open in the browser instead.
 */
@Composable
fun PreviewWindow(preview: WebPreview, root: File, onClose: () -> Unit) {
    val server = remember(root) { PreviewServer(root) }
    val lifecycle = remember(server) { PreviewLifecycle(PreviewLifecycle.IDLE_MS, start = { server.start(it) }, stop = { server.stop() }) }
    var port by remember { mutableStateOf<Int?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loadedUrl by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycle) {
        onDispose { synchronized(lifecycle) { lifecycle.close() } }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        // Focused = the app is resumed and this window has input focus.
        val owner = LocalLifecycleOwner.current
        var resumed by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
        DisposableEffect(owner) {
            val observer = LifecycleEventObserver { _, e ->
                if (e == Lifecycle.Event.ON_RESUME) resumed = true
                if (e == Lifecycle.Event.ON_PAUSE) resumed = false
            }
            owner.lifecycle.addObserver(observer)
            onDispose { owner.lifecycle.removeObserver(observer) }
        }
        val focused = resumed && LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(focused) {
            if (focused) {
                failed = null
                port = runCatching { withContext(Dispatchers.IO) { synchronized(lifecycle) { lifecycle.focus(); lifecycle.port } } }
                    .onFailure { failed = it.message ?: "The preview server could not start." }
                    .getOrNull()
            } else {
                synchronized(lifecycle) { lifecycle.blur(System.currentTimeMillis()) }
                while (port != null) {
                    delay(1_000)
                    if (synchronized(lifecycle) { lifecycle.tick(System.currentTimeMillis()) }) port = null
                }
            }
        }
        val url = port?.let { "http://127.0.0.1:$it/" + Uri.encode(preview.entry, "/") }
        LaunchedEffect(url, webView) {
            val view = webView ?: return@LaunchedEffect
            if (url != null && url != loadedUrl) {
                loadedUrl = url
                view.loadUrl(url)
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .softSurface(RoundedCornerShape(22.dp), Palette.Surface, elevation = 8.dp)
                .clip(RoundedCornerShape(22.dp)),
        ) {
            TitleBar(preview, live = port != null, failed = failed, onReload = { webView?.reload() }, onClose = onClose)
            Box(Modifier.fillMaxWidth().weight(1f).background(Palette.Ink)) {
                AndroidView(
                    factory = { ctx -> secureWebView(ctx) { port }.also { webView = it } },
                    onRelease = { it.stopLoading(); it.destroy(); webView = null },
                    modifier = Modifier.fillMaxSize(),
                )
                if (port == null) {
                    Text(
                        failed ?: "Paused \u2014 starts again when you come back",
                        style = MaterialTheme.typography.labelMedium,
                        color = Palette.TextSecondary,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 12.dp)
                            .softSurface(RoundedCornerShape(50), Palette.SurfaceHigh, elevation = 2.dp)
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TitleBar(preview: WebPreview, live: Boolean, failed: String?, onReload: () -> Unit, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(7.dp).clip(CircleShape).background(
                when {
                    failed != null -> Palette.Danger
                    live -> Palette.Accent
                    else -> Palette.TextTertiary
                },
            ),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(preview.title, style = MaterialTheme.typography.titleSmall, color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                preview.path + (if (preview.entry != "index.html") "/" + preview.entry else "") + if (live) " \u00b7 live" else " \u00b7 paused",
                style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        QuietIconButton(R.drawable.ic_refresh, "Reload", onReload, enabled = live, iconSize = 20.dp)
        QuietIconButton(R.drawable.ic_close, "Close preview", onClose, iconSize = 20.dp)
    }
}

/** A WebView that only shows the local preview server; see [PreviewWindow] for what is locked. */
@SuppressLint("SetJavaScriptEnabled")
private fun secureWebView(context: android.content.Context, port: () -> Int?): WebView = WebView(context).apply {
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        @Suppress("DEPRECATION")
        allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        allowUniversalAccessFromFileURLs = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        setGeolocationEnabled(false)
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        cacheMode = WebSettings.LOAD_NO_CACHE
        mediaPlaybackRequiresUserGesture = true
    }
    webChromeClient = WebChromeClient()
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val u = request.url
            if (u.host == "127.0.0.1" && u.port == port()) return false
            // Anything else leaves the preview: real links open in the browser, other schemes are dropped.
            if (request.isForMainFrame && (u.scheme == "http" || u.scheme == "https")) {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            return true
        }
    }
}
