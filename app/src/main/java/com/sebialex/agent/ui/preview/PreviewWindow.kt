package com.sebialex.agent.ui.preview

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLifecycleOwner
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The preview window of a web app the agent built: a near-full-screen sheet with a title bar
 * (the app's name, reload, close) over a WebView of the app.
 *
 * The app is served from its workspace folder by a [PreviewServer] on 127.0.0.1, which runs
 * while this window is open and the app is in front: it starts when the window opens (or the
 * app comes back), keeps running through short trips away, stops [PreviewLifecycle.IDLE_MS]
 * after the activity stopped, and always stops when the window closes. Window focus is not
 * used at all (see [PreviewLifecycle]). The page keeps its address across a pause when the
 * port is still free. The agent can rebuild files meanwhile; reload shows them (nothing is cached).
 *
 * Nothing is loaded before the socket listens: every URL comes from [PreviewLifecycle.resume],
 * which returns only after the bind, on first open, on coming back, on reload and on retry.
 *
 * The WebView is locked down: JavaScript and DOM storage for the app, but no file or content
 * access, no file-URL cross access, no mixed content, no geolocation, no popups and no bridge
 * to the app; links leaving the local server open in the browser instead.
 *
 * A page that fails to load anyway never stays on the system's error page: the window makes
 * sure the server runs and loads it again, a few times on its own and always on reload or on
 * coming back to the app.
 */
@Composable
fun PreviewWindow(preview: WebPreview, root: File, onClose: () -> Unit) {
    val server = remember(root) { PreviewServer(root) }
    val lifecycle = remember(server) {
        PreviewLifecycle(PreviewLifecycle.IDLE_MS, start = { server.start(it) }, stop = { server.stop() }, alive = { server.running })
    }
    // The port pages are loaded from: set only from a started server, cleared when it stops.
    var port by remember { mutableStateOf<Int?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loadedUrl by remember { mutableStateOf<String?>(null) }
    // The page the WebView shows is an error page, and how many times it was retried without
    // loading anything (a manual reload always gets a fresh set).
    var broken by remember { mutableStateOf(false) }
    var retries by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    fun pageUrl(p: Int) = "http://${PreviewServer.HOST}:$p/" + Uri.encode(preview.entry, "/")

    /**
     * The one way to a port: makes sure the server listens (binding off the main thread) and
     * returns its port, or null when it could not start (the reason is shown) or the window
     * closed. A cancelled caller stays cancelled; the server state lives in [lifecycle] anyway.
     */
    suspend fun ensureServer(): Int? {
        val p = try {
            withContext(Dispatchers.IO) { lifecycle.resume() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = e.message ?: "The preview server could not start."
            return null
        }
        if (p != null) failed = null
        port = p
        return p
    }

    // In front = the activity is resumed; it counts as away from when it stops (a pause with the
    // window still on screen, as in split screen or under a system dialog, does not count). Read
    // here, outside the dialog, from the screen's owner, which follows the activity.
    val owner = LocalLifecycleOwner.current
    var inFront by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) inFront = true
            if (e == Lifecycle.Event.ON_STOP) inFront = false
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    /**
     * Loads the page again, making sure the server runs first. A failed page is retried a few
     * times on its own; after that it waits for a reload or the app coming back.
     */
    fun reload(view: WebView, manual: Boolean) {
        if (manual) retries = 0
        else if (retries >= MAX_RETRIES) {
            failed = "The preview server could not be reached. Reload to try again."
            return
        }
        if (!manual) retries += 1
        scope.launch {
            // An automatic retry stops once the app has gone to the background: it would restart
            // the server there and cancel its idle countdown. Coming back loads the page again.
            if (!manual && !inFront) return@launch
            val p = ensureServer() ?: return@launch
            if (!manual) delay(250)
            val url = pageUrl(p)
            loadedUrl = url
            view.loadUrl(url)
        }
    }

    LaunchedEffect(inFront) {
        if (inFront) {
            val p = ensureServer()
            // A page left on the error page (the app was away long enough for the server to
            // stop) is loaded again now that the server is back.
            if (p != null && broken) webView?.let { reload(it, manual = true) }
        } else {
            lifecycle.background(System.currentTimeMillis())
            while (lifecycle.port != null) {
                delay(1_000)
                if (lifecycle.tick(System.currentTimeMillis())) break
            }
            port = lifecycle.port
        }
    }
    DisposableEffect(lifecycle) {
        onDispose { lifecycle.close() }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        val url = port?.let { pageUrl(it) }
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
            TitleBar(preview.title, onReload = { webView?.let { reload(it, manual = true) } }, onClose = onClose)
            Box(Modifier.fillMaxWidth().weight(1f).background(Palette.Ink)) {
                AndroidView(
                    factory = { ctx -> secureWebView(ctx, { port }, onMainFrameError = { broken = true; reload(it, manual = false) }, onPageShown = { broken = false; retries = 0 }).also { webView = it } },
                    onRelease = { it.stopLoading(); it.destroy(); webView = null },
                    modifier = Modifier.fillMaxSize(),
                )
                // Only a real problem is shown over the page; there is no running/paused status.
                failed?.let { reason ->
                    Text(
                        reason,
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

/** The app's name and nothing else, then reload (which also restarts a stopped server) and close. */
@Composable
private fun TitleBar(title: String, onReload: () -> Unit, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title, style = MaterialTheme.typography.titleSmall, color = Palette.TextPrimary,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        QuietIconButton(R.drawable.ic_refresh, "Reload", onReload, iconSize = 20.dp)
        QuietIconButton(R.drawable.ic_close, "Close preview", onClose, iconSize = 20.dp)
    }
}

/** A WebView that only shows the local preview server; see [PreviewWindow] for what is locked. */
@SuppressLint("SetJavaScriptEnabled")
private fun secureWebView(
    context: android.content.Context,
    port: () -> Int?,
    onMainFrameError: (WebView) -> Unit,
    onPageShown: () -> Unit,
): WebView = WebView(context).apply {
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
        /** The main-frame load currently in flight already failed; a finish is then no success. */
        private var mainLoadFailed = false

        private fun local(u: Uri) = u.host == PreviewServer.HOST && u.port == port()
        private fun local(u: String) = u.startsWith("http://${PreviewServer.HOST}:${port() ?: -1}")

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (local(url)) mainLoadFailed = false
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val u = request.url
            if (local(u)) return false
            // Anything else leaves the preview: real links open in the browser, other schemes are dropped.
            if (request.isForMainFrame && (u.scheme == "http" || u.scheme == "https")) {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            return true
        }

        // The page could not be loaded (the server was not up yet, or was stopped meanwhile): the
        // system's raw error page never stays; the window makes sure the server runs and retries.
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame && local(request.url)) {
                mainLoadFailed = true
                onMainFrameError(view)
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (local(url) && !mainLoadFailed) onPageShown()
        }
    }
}

private const val MAX_RETRIES = 3
