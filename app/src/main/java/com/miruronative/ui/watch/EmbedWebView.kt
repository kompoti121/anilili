package com.miruronative.ui.watch

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient.CustomViewCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.miruronative.data.model.SkipTimes
import com.miruronative.data.settings.SettingsStore
import com.miruronative.diagnostics.CrashReporter
import com.miruronative.ui.adaptive.LocalAppDeviceProfile

/**
 * Renders an embed/iframe provider (or the live site) in a WebView. This is both the player for
 * `type:"embed"` sources and the durable fallback when the native path is Cloudflare-blocked.
 *
 * Main-frame navigation is pinned to the embed's own host: ad-funded embed pages fire same-frame
 * redirects that would otherwise replace the video with an ad page. Resource and iframe loads are
 * unaffected, so the players themselves keep working.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EmbedWebView(
    url: String,
    referer: String?,
    modifier: Modifier = Modifier,
    skip: SkipTimes? = null,
    onNextEpisode: (() -> Unit)? = null,
    onFullscreenChanged: (Boolean) -> Unit = {},
    onProgress: ((positionMs: Long, durationMs: Long) -> Unit)? = null,
) {
    val device = LocalAppDeviceProfile.current
    val lifecycleOwner = LocalContext.current.findLifecycleOwner()
    var loadError by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val currentOnFullscreenChanged by rememberUpdatedState(onFullscreenChanged)
    val currentOnProgress by rememberUpdatedState(onProgress)
    val currentOnNextEpisode by rememberUpdatedState(onNextEpisode)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var positionMs by remember { mutableLongStateOf(0L) }
    val autoSkipIntroOutro by SettingsStore.autoSkipIntroOutro.collectAsState()
    val autoplay by SettingsStore.autoplay.collectAsState()
    val introStartMs = skip?.introStart?.times(1000)?.toLong() ?: 0L
    val introEndMs = skip?.introEnd?.times(1000)?.toLong()
    val outroStartMs = skip?.outroStart?.times(1000)?.toLong()
    val outroEndMs = skip?.outroEnd?.times(1000)?.toLong()
    var introAutoSkipped by remember(url, introStartMs, introEndMs) { mutableStateOf(false) }
    var outroAutoHandled by remember(url, outroStartMs, outroEndMs) { mutableStateOf(false) }

    // Registrable-ish host the embed is allowed to navigate within (its own host + subdomains).
    val allowedHost = remember(url) {
        runCatching { Uri.parse(url).host }.getOrNull()?.lowercase()?.removePrefix("www.")
    }
    // Once the embed page has finished loading, the main frame is locked: a real player never
    // navigates its own top frame again, so any later navigation is an ad hijack — including
    // same-host popunder gateways that the host allowlist alone would let through.
    val navigationLock = remember { object { @Volatile var locked = false } }
    // Compare against the URL we last requested, not WebView.url: pages rewrite their own URL
    // (history.replaceState), which would otherwise re-trigger loadUrl on every recomposition.
    val lastRequestedUrl = remember { object { var value: String? = null } }

    // Called from the WebView's JS bridge thread while the page's accessible <video> plays.
    val progressBridge = remember(mainHandler) {
        object {
            @JavascriptInterface
            fun onTick(positionSec: Double, durationSec: Double) {
                if (positionSec <= 0 || durationSec <= 0) return
                val nextPositionMs = (positionSec * 1000).toLong()
                val nextDurationMs = (durationSec * 1000).toLong()
                mainHandler.post {
                    positionMs = nextPositionMs
                    currentOnProgress?.invoke(nextPositionMs, nextDurationMs)
                }
            }
        }
    }

    val chromeClient = remember {
        object : WebChromeClient() {
            // With multiple windows enabled, window.open lands here instead of navigating the
            // main frame. Returning false swallows the popunder without disturbing playback.
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?,
            ): Boolean = false

            // Android's custom-view fullscreen detaches the page's <video> into a separate
            // surface, and the rotation we trigger right after tears that surface down —
            // Chromium exits fullscreen and the embed player re-initializes mid-episode.
            // Embed pages are full-bleed players already, so fullscreen is implemented by
            // denying the custom view and expanding the WebView natively instead.
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                callback?.onCustomViewHidden()
                currentOnFullscreenChanged(true)
            }

            override fun onHideCustomView() {
                currentOnFullscreenChanged(false)
            }
        }
    }

    val webClient = remember(allowedHost, navigationLock) {
        object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val target = request?.url ?: return true
                if (!request.isForMainFrame) return false
                if (navigationLock.locked) return true // page is up; any top-frame nav is an ad
                val host = target.host.orEmpty().lowercase().removePrefix("www.")
                val allowed = allowedHost != null &&
                    (host == allowedHost || host.endsWith(".$allowedHost"))
                return !allowed // block ad redirects that navigate away from the embed
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                loadError = null
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                navigationLock.locked = true
                view?.evaluateJavascript(PROGRESS_POLL_JS, null)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    loadError = error?.description?.toString() ?: "The server did not respond"
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?,
            ) {
                if (request?.isForMainFrame == true) {
                    loadError = "HTTP ${errorResponse?.statusCode ?: "error"} from the video server"
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner, webView) {
        val owner = lifecycleOwner
        val web = webView
        if (owner == null || web == null) {
            onDispose {}
        } else {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE,
                    Lifecycle.Event.ON_STOP -> {
                        runCatching { web.evaluateJavascript(PAUSE_VIDEO_JS, null) }
                        web.onPause()
                        web.pauseTimers()
                    }
                    Lifecycle.Event.ON_RESUME,
                    Lifecycle.Event.ON_START -> {
                        web.resumeTimers()
                        web.onResume()
                    }
                    else -> Unit
                }
            }
            owner.lifecycle.addObserver(observer)
            onDispose { owner.lifecycle.removeObserver(observer) }
        }
    }

    LaunchedEffect(
        autoSkipIntroOutro,
        autoplay,
        webView,
        positionMs,
        introStartMs,
        introEndMs,
        outroStartMs,
        outroEndMs,
    ) {
        if (!autoSkipIntroOutro || positionMs <= 0L) return@LaunchedEffect

        if (!introAutoSkipped && isInSkipWindow(positionMs, introStartMs, introEndMs)) {
            introAutoSkipped = true
            seekWebVideo(webView, introEndMs)
            return@LaunchedEffect
        }

        if (
            autoplay &&
            !outroAutoHandled &&
            currentOnNextEpisode != null &&
            isInSkipWindow(positionMs, outroStartMs, outroEndMs)
        ) {
            outroAutoHandled = true
            currentOnNextEpisode?.invoke()
        }
    }

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                try {
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        setBackgroundColor(android.graphics.Color.BLACK)
                        isFocusable = true
                        isFocusableInTouchMode = true
                        with(settings) {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            mediaPlaybackRequiresUserGesture = false
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            allowFileAccess = false
                            allowContentAccess = false
                            // Route window.open through onCreateWindow (dropped there) instead
                            // of letting popunders replace the player in the main frame.
                            setSupportMultipleWindows(true)
                            javaScriptCanOpenWindowsAutomatically = false
                            userAgentString = userAgentString.replace("; wv", "") // look less like a webview
                        }
                        addJavascriptInterface(progressBridge, "AniliProgress")
                        webViewClient = webClient
                        webChromeClient = chromeClient
                        if (device.isTv) post { requestFocus() }
                        webView = this
                    }
                } catch (e: Throwable) {
                    CrashReporter.logNonFatal("System WebView unavailable; embed player disabled", e)
                    View(ctx).apply { setBackgroundColor(android.graphics.Color.BLACK) }
                }
            },
            update = { view ->
                val web = view as? WebView ?: return@AndroidView
                web.webViewClient = webClient
                val headers = referer?.let { mapOf("Referer" to it) } ?: emptyMap()
                if (lastRequestedUrl.value != url) {
                    lastRequestedUrl.value = url
                    navigationLock.locked = false // allow the new embed's own redirect chain
                    web.loadUrl(url, headers)
                }
            },
            onRelease = { view ->
                val web = view as? WebView ?: return@AndroidView
                if (webView === web) webView = null
                runCatching { web.evaluateJavascript(PAUSE_VIDEO_JS, null) }
                web.onPause()
                web.pauseTimers()
                web.stopLoading()
                web.removeJavascriptInterface("AniliProgress")
                web.loadUrl("about:blank")
                web.clearHistory()
                web.removeAllViews()
                web.webChromeClient = null
                web.webViewClient = WebViewClient()
                web.destroy()
            },
        )

        loadError?.let { message ->
            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "This server's page failed to load",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "$message — try another server below.",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        val action: Pair<String, () -> Unit>? = when {
            introEndMs != null && isInSkipWindow(positionMs, introStartMs, introEndMs) ->
                "Skip Intro" to { seekWebVideo(webView, introEndMs) }
            outroStartMs != null &&
                outroEndMs != null &&
                currentOnNextEpisode != null &&
                isInSkipWindow(positionMs, outroStartMs, outroEndMs) ->
                "Next Episode" to { currentOnNextEpisode?.invoke() }
            else -> null
        }
        action?.let { (label, onClick) ->
            WebSkipButton(
                label = label,
                onClick = onClick,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
    }
}

@Composable
private fun WebSkipButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(3.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.55f)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.Black.copy(alpha = 0.5f),
            contentColor = Color.White,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        modifier = modifier
            .padding(start = 24.dp, bottom = 24.dp),
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Every web player ultimately drives an HTML5 <video>. Poll it (and any same-origin iframe's)
 * every second while playing and report position/duration to the Kotlin bridge. Cross-origin
 * iframes are unreachable by design — those hosts simply won't report progress.
 */
private val PROGRESS_POLL_JS = """
    (function() {
      if (window.__aniliProgressHooked) return;
      window.__aniliProgressHooked = true;
      function findVideo() {
        var v = document.querySelector('video');
        if (v) return v;
        var frames = document.querySelectorAll('iframe');
        for (var i = 0; i < frames.length; i++) {
          try {
            var d = frames[i].contentDocument;
            if (d) {
              var fv = d.querySelector('video');
              if (fv) return fv;
            }
          } catch (e) { /* cross-origin */ }
        }
        return null;
      }
      setInterval(function() {
        try {
          var v = findVideo();
          if (v && !v.paused && isFinite(v.duration) && v.duration > 0 && v.currentTime > 0) {
            AniliProgress.onTick(v.currentTime, v.duration);
          }
        } catch (e) { /* bridge detached */ }
      }, 1000);
    })();
""".trimIndent()

private fun seekWebVideo(webView: WebView?, targetMs: Long?) {
    val targetSec = targetMs?.div(1000.0) ?: return
    runCatching { webView?.evaluateJavascript(SEEK_VIDEO_JS(targetSec), null) }
}

private fun SEEK_VIDEO_JS(targetSec: Double): String = """
    (function() {
      function findVideo() {
        var v = document.querySelector('video');
        if (v) return v;
        var frames = document.querySelectorAll('iframe');
        for (var i = 0; i < frames.length; i++) {
          try {
            var d = frames[i].contentDocument;
            if (d) {
              var fv = d.querySelector('video');
              if (fv) return fv;
            }
          } catch (e) { /* cross-origin */ }
        }
        return null;
      }
      try {
        var v = findVideo();
        if (!v) return false;
        var target = $targetSec;
        v.currentTime = isFinite(v.duration) && v.duration > 0 ? Math.min(target, v.duration) : target;
        if (v.paused) v.play();
        return true;
      } catch (e) {
        return false;
      }
    })();
""".trimIndent()

private val PAUSE_VIDEO_JS = """
    (function() {
      function pauseVideos(root) {
        var videos = root.querySelectorAll('video');
        for (var i = 0; i < videos.length; i++) {
          videos[i].pause();
        }
      }
      try {
        pauseVideos(document);
        var frames = document.querySelectorAll('iframe');
        for (var i = 0; i < frames.length; i++) {
          try {
            var d = frames[i].contentDocument;
            if (d) pauseVideos(d);
          } catch (e) { /* cross-origin */ }
        }
      } catch (e) { /* ignored */ }
    })();
""".trimIndent()

private tailrec fun Context.findLifecycleOwner(): LifecycleOwner? = when (this) {
    is LifecycleOwner -> this
    is ContextWrapper -> baseContext.findLifecycleOwner()
    else -> null
}

private fun isInSkipWindow(positionMs: Long, startMs: Long?, endMs: Long?): Boolean {
    val start = startMs ?: 0L
    val end = endMs ?: return false
    return end > start && positionMs in start until end
}
