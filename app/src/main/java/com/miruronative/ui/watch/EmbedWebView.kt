package com.miruronative.ui.watch

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient.CustomViewCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.miruronative.data.model.SkipTimes
import com.miruronative.data.model.StreamItem
import com.miruronative.data.settings.CaptionEdgeStyle
import com.miruronative.data.settings.CaptionStyle
import com.miruronative.data.settings.SettingsStore
import com.miruronative.diagnostics.CrashReporter
import com.miruronative.diagnostics.DiagnosticsLog
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import com.miruronative.ui.adaptive.LocalAppDeviceProfile
import com.miruronative.ui.adaptive.rememberScreenReaderActive
import com.miruronative.ui.components.CaptionAppearanceDialog
import kotlinx.coroutines.delay

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
    qualityStreams: List<StreamItem> = emptyList(),
    startPositionMs: Long = 0L,
    skip: SkipTimes? = null,
    onPreviousEpisode: (() -> Unit)? = null,
    onNextEpisode: (() -> Unit)? = null,
    hasPreviousEpisode: Boolean = false,
    hasNextEpisode: Boolean = false,
    focusPlayerOnStart: Boolean = true,
    isFullscreen: Boolean = false,
    onToggleFullscreen: (() -> Unit)? = null,
    onFullscreenChanged: (Boolean) -> Unit = {},
    onProgress: ((positionMs: Long, durationMs: Long) -> Unit)? = null,
    onPlaybackError: ((message: String, streamUrl: String, positionMs: Long) -> Unit)? = null,
    onPlaybackStopperChanged: (((() -> Unit)?) -> Unit)? = null,
) {
    val device = LocalAppDeviceProfile.current
    val context = LocalContext.current
    val lifecycleOwner = context.findLifecycleOwner()
    DisposableEffect(Unit) { onDispose { resetPlayerBrightness(context) } }
    val embedQualityStreams = remember(url, qualityStreams) {
        qualityStreams
            .filter(StreamItem::isEmbed)
            .distinctBy { it.height ?: declaredVideoHeight(it.quality) }
            .filter { (it.height ?: declaredVideoHeight(it.quality)) != null }
            .sortedByDescending { it.height ?: declaredVideoHeight(it.quality) }
    }
    var activeUrl by remember(url) { mutableStateOf(url) }
    val activeQualityStream = embedQualityStreams.firstOrNull { it.url == activeUrl }
    val activeReferer = activeQualityStream?.referer ?: referer
    var captionAppearanceVisible by remember(url) { mutableStateOf(false) }
    var settingsSheetVisible by remember(url) { mutableStateOf(false) }
    var playbackSpeed by remember(url) { mutableStateOf(1f) }
    // The speed to restore once a hold-for-2x gesture ends (the user's chosen playback speed).
    var preHoldSpeed by remember(url) { mutableStateOf(1f) }
    var webPlaybackAvailable by remember(activeUrl) { mutableStateOf(false) }
    var pendingSeekMs by remember(url, startPositionMs) { mutableLongStateOf(startPositionMs) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var finishedUrl by remember(activeUrl) { mutableStateOf<String?>(null) }
    val currentOnPlaybackStopperChanged by rememberUpdatedState(onPlaybackStopperChanged)
    val currentOnFullscreenChanged by rememberUpdatedState(onFullscreenChanged)
    val currentOnProgress by rememberUpdatedState(onProgress)
    val currentOnPreviousEpisode by rememberUpdatedState(onPreviousEpisode)
    val currentOnNextEpisode by rememberUpdatedState(onNextEpisode)
    val currentHasPreviousEpisode by rememberUpdatedState(hasPreviousEpisode)
    val currentHasNextEpisode by rememberUpdatedState(hasNextEpisode)
    // The WebView is built once, so the remote handler below has to read this live rather than
    // capture the value it was created with.
    val currentPlayerOwnsRemote by rememberUpdatedState(focusPlayerOnStart)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val currentPendingSeekMs by rememberUpdatedState(pendingSeekMs)
    var positionMs by remember(url) { mutableLongStateOf(startPositionMs) }
    var durationMs by remember(url) { mutableLongStateOf(0L) }
    var webIsPlaying by remember(url) { mutableStateOf(false) }
    var webVolume by remember(url) { mutableStateOf(1f) }
    var lastAudibleVolume by remember(url) { mutableStateOf(1f) }
    // The device media stream remains the volume/mute fallback for old WebView builds that do not
    // support document-start injection into cross-origin frames.
    val embedAudioManager = remember(context) {
        context.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
    }
    var deviceVolume by remember { mutableStateOf(readDeviceVolume(embedAudioManager)) }
    var tvControlsVisible by remember(url) { mutableStateOf(false) }
    var tvControlsInteraction by remember(url) { mutableIntStateOf(0) }
    var touchControlsVisible by remember(url) { mutableStateOf(true) }
    var touchControlsInteraction by remember(url) { mutableIntStateOf(0) }
    var playbackGestureIsPlaying by remember(url) { mutableStateOf<Boolean?>(null) }
    var fallbackControlsVisible by remember(url) { mutableStateOf(true) }
    var fallbackInteraction by remember(url) { mutableIntStateOf(0) }
    // Compatibility state for old WebViews where a cross-origin frame cannot report playback.
    var fallbackIsPlaying by remember(url) { mutableStateOf(true) }
    val tvPlayPauseFocus = remember { FocusRequester() }
    val currentActiveUrl by rememberUpdatedState(activeUrl)
    val currentPositionMs by rememberUpdatedState(positionMs)
    val currentOnPlaybackError by rememberUpdatedState(onPlaybackError)
    val autoSkipIntroOutro by SettingsStore.autoSkipIntroOutro.collectAsState()
    val autoplay by SettingsStore.autoplay.collectAsState()
    val captionStyle by SettingsStore.captionStyle.collectAsState()
    val introStartMs = skip?.introStart?.times(1000)?.toLong() ?: 0L
    val introEndMs = skip?.introEnd?.times(1000)?.toLong()
    val outroStartMs = skip?.outroStart?.times(1000)?.toLong()
    val outroEndMs = skip?.outroEnd?.times(1000)?.toLong()
    var introAutoSkipped by remember(activeUrl, introStartMs, introEndMs) { mutableStateOf(false) }
    var outroAutoHandled by remember(activeUrl, outroStartMs, outroEndMs) { mutableStateOf(false) }

    DisposableEffect(webView) {
        val web = webView
        currentOnPlaybackStopperChanged?.invoke(web?.let { { stopWebPlayback(it) } })
        onDispose { currentOnPlaybackStopperChanged?.invoke(null) }
    }

    // Full touch controls — seek bar and all — whenever the injected JS can reach the <video>.
    val touchControlsActive = !device.isTv && webPlaybackAvailable && loadError == null
    // Old WebView builds without all-frame injection get a reduced native bar. Modern WebViews
    // report even cross-origin video through the document-start bridge and use the full controls.
    val fallbackControlsActive = !device.isTv && !webPlaybackAvailable && loadError == null

    LaunchedEffect(touchControlsActive, touchControlsVisible, touchControlsInteraction, webIsPlaying) {
        if (!touchControlsActive || !touchControlsVisible || !webIsPlaying) return@LaunchedEffect
        delay(4_000)
        touchControlsVisible = false
    }

    LaunchedEffect(playbackGestureIsPlaying) {
        if (playbackGestureIsPlaying != null) {
            delay(650)
            playbackGestureIsPlaying = null
        }
    }

    LaunchedEffect(fallbackControlsActive, fallbackControlsVisible, fallbackInteraction) {
        if (!fallbackControlsActive || !fallbackControlsVisible) return@LaunchedEffect
        delay(4_000)
        fallbackControlsVisible = false
    }
    // Meet the video with the bar once the page is up, the way the first tap would.
    LaunchedEffect(fallbackControlsActive, finishedUrl) {
        if (fallbackControlsActive && finishedUrl != null) fallbackControlsVisible = true
    }
    LaunchedEffect(tvControlsVisible, focusPlayerOnStart) {
        if (!tvControlsVisible || !focusPlayerOnStart) return@LaunchedEffect
        delay(32)
        runCatching { tvPlayPauseFocus.requestFocus() }
    }
    val screenReaderActive = rememberScreenReaderActive()
    // TalkBack users can't discover the hidden control row through a key press, so present it
    // as soon as the fullscreen player opens instead of waiting for the semantic reveal action.
    LaunchedEffect(screenReaderActive, focusPlayerOnStart, activeUrl) {
        if (device.isTv && screenReaderActive && focusPlayerOnStart) {
            tvControlsVisible = true
        }
    }
    LaunchedEffect(
        tvControlsVisible,
        tvControlsInteraction,
        webView,
        focusPlayerOnStart,
        screenReaderActive,
        settingsSheetVisible,
        captionAppearanceVisible,
    ) {
        if (!focusPlayerOnStart) {
            tvControlsVisible = false
            return@LaunchedEffect
        }
        if (!tvControlsVisible) return@LaunchedEffect
        // TalkBack users navigate slowly and can't reopen the controls with a key press
        // (the screen reader consumes the D-pad), so never auto-hide under a screen reader.
        if (screenReaderActive) return@LaunchedEffect
        // While the settings panel or caption dialog is up, hiding the row would hand focus
        // back to the WebView and take the remote away from the panel.
        if (settingsSheetVisible || captionAppearanceVisible) return@LaunchedEffect
        delay(8_000)
        tvControlsVisible = false
        webView?.requestFocus()
    }

    LaunchedEffect(activeUrl, webView, device.isTv, focusPlayerOnStart, hasPreviousEpisode, hasNextEpisode, screenReaderActive) {
        if (!device.isTv || !focusPlayerOnStart || webView == null) return@LaunchedEffect
        // Under a screen reader the control row is auto-shown and focused; grabbing focus for
        // the WebView here would dump TalkBack back into the embed's web content.
        if (screenReaderActive) return@LaunchedEffect
        delay(250)
        runCatching { webView?.requestFocus() }
    }

    // Registrable-ish host the embed is allowed to navigate within (its own host + subdomains).
    val allowedHost = remember(activeUrl) {
        runCatching { Uri.parse(activeUrl).host }.getOrNull()?.lowercase()?.removePrefix("www.")
    }
    LaunchedEffect(activeUrl) {
        DiagnosticsLog.event("EmbedWebView composed urlHost=${allowedHost ?: "unknown"} refererHost=${activeReferer.hostOrNone()}")
        delay(10_000)
        if (finishedUrl == null && loadError == null) {
            DiagnosticsLog.event("EmbedWebView still loading after 10000ms urlHost=${allowedHost ?: "unknown"}")
        }
    }
    // Once the embed page has finished loading, the main frame is locked: a real player never
    // navigates its own top frame again, so any later navigation is an ad hijack — including
    // same-host popunder gateways that the host allowlist alone would let through.
    val navigationLock = remember { object { @Volatile var locked = false } }
    // Compare against the URL we last requested, not WebView.url: pages rewrite their own URL
    // (history.replaceState), which would otherwise re-trigger loadUrl on every recomposition.
    val lastRequestedUrl = remember { object { var value: String? = null } }

    LaunchedEffect(webPlaybackAvailable, playbackSpeed, webView) {
        val web = webView ?: return@LaunchedEffect
        if (!webPlaybackAvailable) return@LaunchedEffect
        dispatchWebPlayerCommand(web, "speed", playbackSpeed.toDouble(), SET_PLAYBACK_SPEED_JS(playbackSpeed))
    }

    // Kwik (and any other Plyr embed) opens on a poster and waits for a gesture, so the episode
    // just sits at 0:00 until the viewer presses play — the app reads as frozen and every
    // position-driven affordance is blind. The native path starts on its own, so start here too.
    LaunchedEffect(webPlaybackAvailable, webView) {
        val web = webView ?: return@LaunchedEffect
        if (!webPlaybackAvailable) return@LaunchedEffect
        dispatchWebPlayerCommand(web, "play", fallbackScript = START_PLAYBACK_JS)
    }

    // Document-start injection below normally hides provider chrome before its first paint. Repeat
    // once our controls become active for older WebViews that only support main-frame evaluation.
    LaunchedEffect(webPlaybackAvailable, webView) {
        val web = webView ?: return@LaunchedEffect
        if (!webPlaybackAvailable) return@LaunchedEffect
        web.evaluateJavascript(HIDE_PROVIDER_CHROME_JS, null)
    }

    // Best-effort: reaches the main document and same-origin iframes only, exactly like the
    // progress poll. A cross-origin embed renders its own captions out of our reach, and some
    // providers burn subtitles into the video, where there is nothing to style at all.
    LaunchedEffect(webPlaybackAvailable, captionStyle, webView) {
        val web = webView ?: return@LaunchedEffect
        if (!webPlaybackAvailable) return@LaunchedEffect
        web.evaluateJavascript(CAPTION_STYLE_JS(captionStyle), null)
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
            ): Boolean {
                DiagnosticsLog.event("EmbedWebView blocked popup isDialog=$isDialog userGesture=$isUserGesture")
                return false
            }

            // Android's custom-view fullscreen detaches the page's <video> into a separate
            // surface, and the rotation we trigger right after tears that surface down —
            // Chromium exits fullscreen and the embed player re-initializes mid-episode.
            // Embed pages are full-bleed players already, so fullscreen is implemented by
            // denying the custom view and expanding the WebView natively instead.
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                DiagnosticsLog.event("EmbedWebView custom fullscreen requested")
                callback?.onCustomViewHidden()
                currentOnFullscreenChanged(true)
            }

            override fun onHideCustomView() {
                DiagnosticsLog.event("EmbedWebView custom fullscreen hidden")
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
                if (!allowed) DiagnosticsLog.event("EmbedWebView blocked main-frame nav targetHost=$host")
                return !allowed // block ad redirects that navigate away from the embed
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                loadError = null
                finishedUrl = null
                DiagnosticsLog.event("EmbedWebView page started host=${url.hostOrNone()}")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                navigationLock.locked = true
                finishedUrl = url
                DiagnosticsLog.event("EmbedWebView page finished host=${url.hostOrNone()} title=${view?.title ?: "none"}")
                // Fallback for old WebView providers without DOCUMENT_START_SCRIPT. This can only
                // reach the main document; supported providers get every frame at document start.
                view?.evaluateJavascript(HIDE_PROVIDER_CHROME_JS, null)
                view?.evaluateJavascript(PROGRESS_POLL_JS, null)
                if (currentPendingSeekMs > 0L) {
                    view?.let {
                        dispatchWebPlayerCommand(
                            it,
                            "resumeAt",
                            currentPendingSeekMs / 1000.0,
                            RESUME_WHEN_READY_JS(currentPendingSeekMs / 1000.0),
                        )
                    }
                }
            }

            @android.annotation.TargetApi(Build.VERSION_CODES.M)
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    val message = error?.description?.toString() ?: "The server did not respond"
                    loadError = message
                    DiagnosticsLog.event(
                        "EmbedWebView main-frame error code=${error?.errorCode} " +
                            "description=${error?.description} host=${request.url?.host ?: "unknown"}",
                    )
                    currentOnPlaybackError?.invoke(message, currentActiveUrl, currentPositionMs)
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?,
            ) {
                if (request?.isForMainFrame == true) {
                    val message = "HTTP ${errorResponse?.statusCode ?: "error"} from the video server"
                    loadError = message
                    DiagnosticsLog.event(
                        "EmbedWebView main-frame HTTP error status=${errorResponse?.statusCode} " +
                            "reason=${errorResponse?.reasonPhrase} host=${request.url?.host ?: "unknown"}",
                    )
                    currentOnPlaybackError?.invoke(message, currentActiveUrl, currentPositionMs)
                }
            }

            @android.annotation.TargetApi(Build.VERSION_CODES.O)
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                DiagnosticsLog.event(
                    "EmbedWebView render process gone didCrash=${detail?.didCrash()} " +
                        "priority=${detail?.rendererPriorityAtExit()}",
                )
                currentOnPlaybackError?.invoke("Video server renderer stopped", currentActiveUrl, currentPositionMs)
                if (webView === view) webView = null
                return true
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
                        dispatchWebPlayerCommand(web, "pause", fallbackScript = PAUSE_VIDEO_JS)
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

    val remoteModifier = if (device.isTv && focusPlayerOnStart) {
        Modifier
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || !opensTvPlayerControls(event.key)) {
                    return@onPreviewKeyEvent false
                }
                tvControlsInteraction++
                false
            }
            // Screen readers swallow the D-pad, so the WebView key hook never fires under
            // TalkBack; this semantic action is the accessible way to reveal the controls.
            .semantics {
                contentDescription = "Video player"
                onClick(label = "Show player controls") {
                    tvControlsInteraction++
                    tvControlsVisible = true
                    true
                }
            }
    } else {
        Modifier
    }
    Box(modifier.then(remoteModifier)) {
        if (captionAppearanceVisible) {
            CaptionAppearanceDialog(
                onDismiss = { captionAppearanceVisible = false },
                footnote = "This server renders its own subtitles, so it may ignore some of these.",
            )
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                try {
                    DiagnosticsLog.event("EmbedWebView factory create WebView")
                    DiagnosticsLog.webViewPackage("EmbedWebView factory")
                    object : WebView(ctx) {
                        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                            // Only claim the remote while the player is the active surface. In the
                            // TV episode grid the list owns the D-pad: handling keys here ate them
                            // silently (the controls this opens are hidden in that state) and stole
                            // Center from the box that opens the player fullscreen. Report them
                            // unhandled rather than deferring to super, which would let the embed's
                            // own page act on the remote; unhandled keys let the framework move
                            // focus out of the player and back to the list.
                            if (device.isTv && !currentPlayerOwnsRemote) return false
                            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                                when {
                                    device.isTv && opensTvPlayerControls(event.keyCode) -> {
                                        mainHandler.post {
                                            tvControlsVisible = true
                                            tvControlsInteraction++
                                        }
                                        return true
                                    }
                                    event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                                        dispatchWebPlayerCommand(this, "toggle", fallbackScript = REMOTE_TOGGLE_PLAYBACK_JS)
                                        mainHandler.post { webIsPlaying = !webIsPlaying }
                                        return true
                                    }
                                    event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY -> {
                                        dispatchWebPlayerCommand(this, "play", fallbackScript = START_PLAYBACK_JS)
                                        mainHandler.post { webIsPlaying = true }
                                        return true
                                    }
                                    event.keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                                        dispatchWebPlayerCommand(this, "pause", fallbackScript = PAUSE_VIDEO_JS)
                                        mainHandler.post { webIsPlaying = false }
                                        return true
                                    }
                                    event.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND -> {
                                        mainHandler.post {
                                            seekWebVideo(this, (currentPositionMs - 10_000L).coerceAtLeast(0L))
                                        }
                                        return true
                                    }
                                    event.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                                        mainHandler.post { seekWebVideo(this, currentPositionMs + 10_000L) }
                                        return true
                                    }
                                    event.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT && currentHasNextEpisode -> {
                                        currentOnNextEpisode?.invoke()
                                        return true
                                    }
                                    event.keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS && currentHasPreviousEpisode -> {
                                        currentOnPreviousEpisode?.invoke()
                                        return true
                                    }
                                }
                            }
                            return super.dispatchKeyEvent(event)
                        }
                    }.apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        setBackgroundColor(android.graphics.Color.BLACK)
                        isFocusable = !device.isTv || focusPlayerOnStart
                        isFocusableInTouchMode = !device.isTv || focusPlayerOnStart
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
                        // Construct the concrete bridge at registration so Android lint can
                        // verify its @JavascriptInterface methods through Kotlin's apply block.
                        addJavascriptInterface(
                            WebProgressBridge(
                                onTickCallback = { positionSec, durationSec, isPlaying, muted, volume ->
                                    if (positionSec > 0 && durationSec > 0) {
                                        val nextPositionMs = (positionSec * 1000).toLong()
                                        val nextDurationMs = (durationSec * 1000).toLong()
                                        mainHandler.post {
                                            positionMs = nextPositionMs
                                            durationMs = nextDurationMs
                                            webIsPlaying = isPlaying
                                            webVolume = if (muted) 0f else volume.toFloat().coerceIn(0f, 1f)
                                            if (isPlaying) currentOnProgress?.invoke(nextPositionMs, nextDurationMs)
                                        }
                                    }
                                },
                                onVideoAvailableCallback = {
                                    mainHandler.post { webPlaybackAvailable = true }
                                },
                            ),
                            "AniliProgress",
                        )
                        webViewClient = webClient
                        webChromeClient = chromeClient
                        installWebPlayerBootstrap(this)
                        webView = this
                        DiagnosticsLog.event("EmbedWebView factory complete userAgent=${settings.userAgentString.take(100)}")
                    }
                } catch (e: Throwable) {
                    CrashReporter.logNonFatal("System WebView unavailable; embed player disabled", e)
                    View(ctx).apply { setBackgroundColor(android.graphics.Color.BLACK) }
                }
            },
            update = { view ->
                val web = view as? WebView ?: return@AndroidView
                web.isFocusable = !device.isTv || focusPlayerOnStart
                web.isFocusableInTouchMode = !device.isTv || focusPlayerOnStart
                if (device.isTv && !focusPlayerOnStart) web.clearFocus()
                web.webViewClient = webClient
                val headers = activeReferer?.let { mapOf("Referer" to it) } ?: emptyMap()
                if (lastRequestedUrl.value != activeUrl) {
                    lastRequestedUrl.value = activeUrl
                    navigationLock.locked = false // allow the new embed's own redirect chain
                    DiagnosticsLog.event("EmbedWebView loadUrl host=${activeUrl.hostOrNone()} headers=${headers.keys.joinToString()}")
                    if (activeUrl.requiresAllAnimeIframeShell(activeReferer)) {
                        // OK.ru returns a blank document when opened as a top-level WebView page.
                        // The captured browser flow loads it as an iframe from AllAnime, which
                        // also supplies the required iframe navigation metadata and referer.
                        web.loadDataWithBaseURL(
                            activeReferer,
                            allAnimeIframeShell(activeUrl),
                            "text/html",
                            "UTF-8",
                            null,
                        )
                    } else {
                        web.loadUrl(activeUrl, headers)
                    }
                }
            },
            onRelease = { view ->
                val web = view as? WebView ?: return@AndroidView
                if (webView === web) webView = null
                DiagnosticsLog.event("EmbedWebView release url=${web.url ?: "none"} size=${web.width}x${web.height}")
                stopWebPlayback(web)
                web.removeJavascriptInterface("AniliProgress")
                web.clearHistory()
                web.removeAllViews()
                web.webChromeClient = null
                web.webViewClient = WebViewClient()
                web.destroy()
            },
        )

        // TV normally reveals this bar from WebView key dispatch. Mouse/air-mouse clicks do not
        // travel through that path, and passing the first click to an embed can also trigger its
        // provider chrome or an ad. Capture empty-video clicks in Compose instead; the controls
        // are drawn later and remain directly clickable when visible.
        if (device.isTv && focusPlayerOnStart && webView != null && loadError == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(tvControlsVisible) {
                        detectTapGestures {
                            tvControlsInteraction++
                            if (tvControlsVisible) {
                                DiagnosticsLog.event("EmbedWebView TV controls closed pointer")
                                tvControlsVisible = false
                            } else {
                                DiagnosticsLog.event("EmbedWebView TV controls opened pointer")
                                tvControlsVisible = true
                            }
                        }
                    },
            )
        }

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

        // Swallows every touch so the page never sees one: web players only raise their control
        // chrome on interaction, so starving them of taps keeps the provider UI hidden and lets
        // our overlay be the only controls. Also neuters tap-hijack ads, which need a real click.
        // Horizontal drag seeks; vertical edge drags control brightness and volume.
        if (touchControlsActive) PlayerGestureControls(
            positionMs = positionMs,
            durationMs = durationMs,
            onTap = {
                touchControlsVisible = !touchControlsVisible
                touchControlsInteraction++
            },
            onDoubleTap = {
                DiagnosticsLog.event("EmbedWebView double tap playPause")
                webView?.let { dispatchWebPlayerCommand(it, "toggle", fallbackScript = REMOTE_TOGGLE_PLAYBACK_JS) }
                webIsPlaying = !webIsPlaying
                playbackGestureIsPlaying = webIsPlaying
            },
            onSeek = { target ->
                seekWebVideo(webView, target)
                positionMs = target
                touchControlsInteraction++
            },
            onHoldSpeed = { active ->
                if (active) {
                    preHoldSpeed = playbackSpeed
                    playbackSpeed = 2f
                } else {
                    playbackSpeed = preHoldSpeed
                }
            },
        )

        // Older WebViews without document-start frame injection cannot expose cross-origin video
        // state. They still never receive pointer input: taps only reveal/hide our fallback row, so
        // provider controls and tap-hijack ads stay inaccessible.
        if (fallbackControlsActive) Box(
            Modifier
                .fillMaxSize()
                .pointerInput(url) {
                    detectTapGestures {
                        fallbackControlsVisible = !fallbackControlsVisible
                        fallbackInteraction++
                    }
                },
        )

        if (touchControlsActive && touchControlsVisible) {
            EmbedTouchControls(
                positionMs = positionMs,
                durationMs = durationMs,
                isPlaying = webIsPlaying,
                hasPrevious = hasPreviousEpisode && currentOnPreviousEpisode != null,
                hasNext = hasNextEpisode && currentOnNextEpisode != null,
                onPrevious = { currentOnPreviousEpisode?.invoke() },
                onRewind = {
                    seekWebVideo(webView, (positionMs - 10_000L).coerceAtLeast(0L))
                    touchControlsInteraction++
                },
                onPlayPause = {
                    DiagnosticsLog.event("EmbedWebView touch control playPause")
                    webView?.let { dispatchWebPlayerCommand(it, "toggle", fallbackScript = REMOTE_TOGGLE_PLAYBACK_JS) }
                    webIsPlaying = !webIsPlaying
                    touchControlsInteraction++
                },
                onForward = {
                    seekWebVideo(webView, positionMs + 10_000L)
                    touchControlsInteraction++
                },
                onNext = { currentOnNextEpisode?.invoke() },
                onSeek = { targetMs ->
                    seekWebVideo(webView, targetMs)
                    positionMs = targetMs // the poll confirms next tick; without this the thumb snaps back first
                    touchControlsInteraction++
                },
                onSettings = { settingsSheetVisible = true },
                isFullscreen = isFullscreen,
                onFullscreen = onToggleFullscreen,
                onInteract = { touchControlsInteraction++ },
            )
        }

        playbackGestureIsPlaying?.let { isPlaying ->
            PlaybackGestureIndicator(isPlaying, Modifier.align(Alignment.Center))
        }

        if (settingsSheetVisible) {
            PlayerSettingsSheet(
                onDismiss = { settingsSheetVisible = false },
                autoplay = autoplay,
                onAutoplayChange = SettingsStore::setAutoplay,
                speed = if (webPlaybackAvailable) playbackSpeed else null,
                onSpeedChange = { playbackSpeed = it },
                qualityOptions = embedQualityStreams.mapNotNull { option ->
                    val height = option.height ?: declaredVideoHeight(option.quality) ?: return@mapNotNull null
                    PlayerQualityOption(
                        label = "${height}p",
                        selected = option.url == activeUrl,
                        onSelect = {
                            pendingSeekMs = positionMs
                            activeUrl = option.url
                        },
                    )
                },
                onCaptionAppearance = if (webPlaybackAvailable) {
                    { captionAppearanceVisible = true }
                } else {
                    null
                },
                autoSkip = autoSkipIntroOutro,
                onAutoSkipChange = SettingsStore::setAutoSkipIntroOutro,
            )
        }

        // Reduced native bar for old WebView builds that cannot report cross-origin position or
        // duration. Provider controls remain suppressed, so seeking is unavailable in this rare
        // compatibility mode instead of exposing the page UI.
        if (fallbackControlsActive && fallbackControlsVisible) Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))),
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlayerControlIconButton(
                    "Previous episode",
                    Icons.Default.SkipPrevious,
                    enabled = hasPreviousEpisode && currentOnPreviousEpisode != null,
                    onClick = { currentOnPreviousEpisode?.invoke() },
                )
                PlayerControlIconButton(
                    if (fallbackIsPlaying) "Pause" else "Play",
                    if (fallbackIsPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    onClick = {
                        DiagnosticsLog.event("EmbedWebView fallback playPause")
                        webView?.let {
                            dispatchWebPlayerCommand(it, "toggle", fallbackScript = REMOTE_TOGGLE_PLAYBACK_JS)
                        }
                        fallbackIsPlaying = !fallbackIsPlaying
                        fallbackInteraction++
                    },
                )
                PlayerControlIconButton(
                    "Next episode",
                    Icons.Default.SkipNext,
                    enabled = hasNextEpisode && currentOnNextEpisode != null,
                    onClick = { currentOnNextEpisode?.invoke() },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlayerControlIconButton(
                    "Settings",
                    Icons.Default.Settings,
                    onClick = {
                        settingsSheetVisible = true
                        fallbackInteraction++
                    },
                )
                onToggleFullscreen?.let { toggle ->
                    PlayerControlIconButton(
                        if (isFullscreen) "Exit fullscreen" else "Fullscreen",
                        if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                        onClick = {
                            toggle()
                            fallbackInteraction++
                        },
                    )
                }
            }
        }

        if (device.isTv && focusPlayerOnStart && tvControlsVisible) {
            TvPlayerControls(
                positionMs = positionMs,
                durationMs = durationMs,
                isPlaying = webIsPlaying,
                isMuted = (if (webPlaybackAvailable) webVolume else deviceVolume) <= 0.001f,
                hasPrevious = hasPreviousEpisode && currentOnPreviousEpisode != null,
                hasNext = hasNextEpisode && currentOnNextEpisode != null,
                playPauseFocusRequester = tvPlayPauseFocus,
                onPrevious = { currentOnPreviousEpisode?.invoke() },
                onRewind = { seekWebVideo(webView, (positionMs - 10_000L).coerceAtLeast(0L)) },
                onPlayPause = {
                    DiagnosticsLog.event("EmbedWebView TV control playPause")
                    webView?.let { dispatchWebPlayerCommand(it, "toggle", fallbackScript = REMOTE_TOGGLE_PLAYBACK_JS) }
                    webIsPlaying = !webIsPlaying
                },
                onForward = { seekWebVideo(webView, positionMs + 10_000L) },
                onNext = { currentOnNextEpisode?.invoke() },
                onToggleMute = {
                    DiagnosticsLog.event("EmbedWebView TV control toggleMute available=$webPlaybackAvailable")
                    if (webPlaybackAvailable) {
                        if (webVolume > 0.001f) lastAudibleVolume = webVolume
                        val target = if (webVolume > 0.001f) 0f else lastAudibleVolume.coerceAtLeast(0.1f)
                        setWebVolume(webView, target) { webVolume = it }
                    } else {
                        val current = readDeviceVolume(embedAudioManager)
                        if (current > 0.001f) lastAudibleVolume = current
                        deviceVolume = if (current > 0.001f) 0f else lastAudibleVolume.coerceAtLeast(0.1f)
                        applyDeviceVolume(embedAudioManager, deviceVolume)
                    }
                },
                onSettings = {
                    // The full settings panel (quality, speed, captions, autoplay, auto-skip,
                    // volume) with the TV side-panel presentation — the old quality-or-speed
                    // dialog left most settings unreachable from a remote.
                    DiagnosticsLog.event("EmbedWebView TV control settings")
                    settingsSheetVisible = true
                },
                onFullscreen = onToggleFullscreen,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // Only offer a skip once the page has actually started playing. Embeds that open on a
        // poster (Kwik) sit at position 0 indefinitely, and an intro window starting at 0 would
        // otherwise pin "Skip Intro" on screen from load until the viewer presses play. A non-zero
        // duration is the poll's proof it reached the <video> and saw it running.
        val skipAvailable = durationMs > 0L
        val action: Pair<String, () -> Unit>? = when {
            !skipAvailable -> null
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
            // Same dodge the native player makes: the bottom-left corner is where the bar draws its
            // seek slider and elapsed time, so while a bar is up the skip button moves to the top
            // instead of sitting on top of them. It only takes the corner when the screen is clear.
            val controlsShowing = when {
                device.isTv -> tvControlsVisible
                touchControlsActive -> touchControlsVisible
                else -> fallbackControlsVisible
            }
            WebSkipButton(
                label = label,
                onClick = onClick,
                modifier = if (controlsShowing) {
                    Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 16.dp)
                } else {
                    Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = 24.dp)
                },
            )
        }
    }
}

private fun String.requiresAllAnimeIframeShell(referer: String?): Boolean {
    val host = runCatching { Uri.parse(this).host }.getOrNull()?.lowercase()?.removePrefix("www.")
    val refererHost = runCatching { Uri.parse(referer).host }.getOrNull()?.lowercase()?.removePrefix("www.")
    return host == "ok.ru" && refererHost == "allanime.day"
}

private fun allAnimeIframeShell(url: String): String {
    val escaped = url
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
    return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head><body style="margin:0;background:#000;overflow:hidden"><iframe src="$escaped" allow="autoplay; fullscreen; encrypted-media; picture-in-picture" allowfullscreen referrerpolicy="no-referrer-when-downgrade" style="position:fixed;inset:0;width:100%;height:100%;border:0"></iframe></body></html>"""
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
        modifier = modifier,
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Legacy main-frame fallback for WebViews without document-start injection. Current WebViews use
 * the all-frame poll installed by [HIDE_PROVIDER_CHROME_JS].
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
          if (v && !window.__aniliVideoReported) {
            window.__aniliVideoReported = true;
            AniliProgress.onVideoAvailable();
          }
          if (v && isFinite(v.duration) && v.duration > 0 && v.currentTime >= 0) {
            AniliProgress.onTick(v.currentTime, v.duration, !v.paused, v.muted, v.volume);
          }
        } catch (e) { /* bridge detached */ }
      }, 1000);
    })();
""".trimIndent()

private fun SET_PLAYBACK_SPEED_JS(speed: Float): String = """
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
        v.defaultPlaybackRate = $speed;
        v.playbackRate = $speed;
        return Math.abs(v.playbackRate - $speed) < 0.01;
      } catch (e) {
        return false;
      }
    })();
""".trimIndent()

private const val CAPTION_STYLE_ELEMENT_ID = "anili-caption-style"

/**
 * Restyles captions in the main document and any same-origin iframe. Idempotent: re-running with a
 * new style rewrites the same `<style>` element instead of stacking duplicates.
 */
private fun CAPTION_STYLE_JS(style: CaptionStyle): String = """
    (function() {
      var css = ${captionCss(style).toJsStringLiteral()};
      function apply(doc) {
        if (!doc) return;
        var el = doc.getElementById('$CAPTION_STYLE_ELEMENT_ID');
        if (!el) {
          el = doc.createElement('style');
          el.id = '$CAPTION_STYLE_ELEMENT_ID';
          (doc.head || doc.documentElement).appendChild(el);
        }
        el.textContent = css;
      }
      try {
        apply(document);
        var frames = document.querySelectorAll('iframe');
        for (var i = 0; i < frames.length; i++) {
          try { apply(frames[i].contentDocument); } catch (e) { /* cross-origin */ }
        }
      } catch (e) { /* ignored */ }
    })();
""".trimIndent()

/**
 * `::cue` covers Chromium's own WebVTT rendering; the class selectors cover the embed players that
 * draw captions as ordinary DOM instead. Ours is appended last, so at equal specificity it wins.
 */
internal fun captionCss(style: CaptionStyle): String {
    val background = style.backgroundCssRgba()
    // `background` first so the shorthand can't reset the colour we set right after it.
    val declarations = "background: $background !important; " +
        "background-color: $background !important; " +
        "color: ${style.textCssHex()} !important; " +
        "text-shadow: ${style.edgeStyle.toCssTextShadow()} !important; " +
        "font-size: ${style.textScalePercent}% !important; " +
        "font-weight: ${if (style.boldText) 700 else 400} !important;"
    val domDeclarations = "$declarations bottom: ${style.bottomMarginPercent}% !important;"
    return "::cue { $declarations }\n$DOM_CAPTION_SELECTORS { $domDeclarations }"
}

private const val DOM_CAPTION_SELECTORS =
    ".plyr__caption, .vjs-text-track-cue > div, .jw-text-track-cue, .art-subtitle"

private fun CaptionEdgeStyle.toCssTextShadow(): String = when (this) {
    CaptionEdgeStyle.NONE -> "none"
    CaptionEdgeStyle.OUTLINE ->
        "-1px -1px 1px #000, 1px -1px 1px #000, -1px 1px 1px #000, 1px 1px 1px #000"
    CaptionEdgeStyle.DROP_SHADOW -> "2px 2px 3px rgba(0, 0, 0, 0.9)"
}

private fun String.toJsStringLiteral(): String =
    "'" + replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'"

/**
 * Starts a paused embed. Only ever calls play() on a video that is actually paused, so a provider
 * that already autoplays is left alone rather than being toggled off. `play()` returns a promise
 * that rejects when the page still insists on a gesture; that is a no-op, not a failure.
 */
private val START_PLAYBACK_JS = """
    (function() {
      if (window.__aniliAutoStarted) return;
      function start(root) {
        var video = root.querySelector('video');
        if (!video || !video.paused) return !!video;
        var play = video.play();
        if (play && play.catch) play.catch(function() {});
        return true;
      }
      try {
        if (start(document)) { window.__aniliAutoStarted = true; return; }
        var frames = document.querySelectorAll('iframe');
        for (var i = 0; i < frames.length; i++) {
          try {
            if (frames[i].contentDocument && start(frames[i].contentDocument)) {
              window.__aniliAutoStarted = true;
              return;
            }
          } catch (e) { /* cross-origin */ }
        }
      } catch (e) { /* best effort */ }
    })();
""".trimIndent()

/**
 * Removes embed-player chrome while leaving video and captions intact. The script is installed at
 * document start in every frame, so it reaches cross-origin Kiwi/Kwik iframes that a later
 * [WebView.evaluateJavascript] call cannot access. The observer makes the suppression survive SPA
 * rerenders and players that recreate their controls after playback begins.
 */
internal val HIDE_PROVIDER_CHROME_JS = """
    (function() {
      if (window.__aniliChromeSuppressionInstalled) return;
      window.__aniliChromeSuppressionInstalled = true;

      var styleId = 'anili-hide-provider-chrome';
      var css = [
        '.plyr__controls',
        '.plyr__control--overlaid',
        '.plyr__menu__container',
        '.vjs-control-bar',
        '.vjs-big-play-button',
        '.jw-controlbar',
        '.jw-display-controls',
        '.jw-title',
        '.jw-logo',
        '.art-controls',
        '.art-center',
        '.art-mask',
        '.art-contextmenus',
        '.media-control',
        '.play-wrapper',
        '.fluid_controls_container',
        '.fluid_initial_play_button',
        '.mejs__controls',
        '.mejs__overlay-play',
        '.shaka-controls-container',
        '.dplayer-controller',
        '.dplayer-mobile-play',
        '.dplayer-menu',
        '.dplayer-logo',
        '.xgplayer-controls',
        '.xgplayer-start',
        '.fp-controls',
        '.fp-play',
        '.playkit-control-bar',
        '.playkit-pre-playback-play-button',
        '.playkit-overlay-button',
        '.op-controls',
        '.op-player__play',
        '[data-media-controls]',
        '[data-media-button]',
        'media-controls',
        'media-play-button',
        '.back-button',
        '.back-btn',
        '.backButton',
        '.player-back',
        '.player__back',
        '.plyr__back',
        '.vjs-back-button',
        '.jw-back-button',
        '.art-back',
        'button[aria-label="Back" i]',
        'button[title="Back" i]',
        '[data-testid="back-button"]',
        '[data-testid="player-back"]',
        '[class~="player-controls"]',
        '[class~="player-control-bar"]',
        '[class~="controls-container"]',
        '[class~="big-play-button"]',
        '[class~="center-play-button"]',
        'html.anili-video-document button',
        'html.anili-video-document [role="button"]',
        'html.anili-video-document [role="slider"]',
        'html.anili-video-document input[type="button"]',
        'html.anili-video-document input[type="range"]',
        'html.anili-video-document select',
        'html.anili-video-document a[aria-label]'
      ].join(',') +
        '{display:none!important;opacity:0!important;visibility:hidden!important;' +
        'pointer-events:none!important;}';
      css += 'video::-webkit-media-controls{display:none!important;}';
      css += 'video::-webkit-media-controls-enclosure{display:none!important;}';
      css += 'video::-webkit-media-controls-panel{display:none!important;}';

      function installStyle() {
        var parent = document.head || document.documentElement;
        if (!parent) return;
        var style = document.getElementById(styleId);
        if (!style) {
          style = document.createElement('style');
          style.id = styleId;
          parent.appendChild(style);
        }
        if (style.textContent !== css) style.textContent = css;
      }

      function suppressNativeControls(root) {
        if (!root) return;
        var videos = [];
        if (root.matches && root.matches('video')) videos.push(root);
        if (root.querySelectorAll) {
          var descendants = root.querySelectorAll('video');
          for (var i = 0; i < descendants.length; i++) videos.push(descendants[i]);
        }
        for (var j = 0; j < videos.length; j++) {
          var video = videos[j];
          video.controls = false;
          video.removeAttribute('controls');
          video.setAttribute('controlsList', 'nodownload nofullscreen noremoteplayback');
          try { video.disablePictureInPicture = true; } catch (e) { /* optional */ }
        }
        if (videos.length && document.documentElement) {
          document.documentElement.classList.add('anili-video-document');
        }
      }

      function suppress(root) {
        installStyle();
        suppressNativeControls(root || document);
      }

      function findVideo() {
        var videos = document.querySelectorAll('video');
        var best = null;
        var bestScore = -1;
        for (var i = 0; i < videos.length; i++) {
          var video = videos[i];
          var rect = video.getBoundingClientRect();
          var score = Math.max(0, rect.width) * Math.max(0, rect.height);
          if (!video.paused) score += 1000000000;
          if (isFinite(video.duration) && video.duration > 60) score += 100000000;
          if (score > bestScore) { best = video; bestScore = score; }
        }
        return best;
      }

      function play(video) {
        if (!video) return false;
        var result = video.play();
        if (result && result.catch) result.catch(function() {});
        return true;
      }

      function applyCommand(data) {
        if (!data || data.__aniliPlayerCommand !== 'v1') return false;
        var video = findVideo();
        if (!video) return false;
        var value = Number(data.value);
        try {
          switch (data.command) {
            case 'play': return play(video);
            case 'pause': video.pause(); return true;
            case 'toggle': if (video.paused) play(video); else video.pause(); return true;
            case 'seek':
              if (!isFinite(value)) return false;
              video.currentTime = isFinite(video.duration) && video.duration > 0
                ? Math.min(Math.max(0, value), video.duration)
                : Math.max(0, value);
              return true;
            case 'resumeAt':
              if (!isFinite(value)) return false;
              video.currentTime = isFinite(video.duration) && video.duration > 0
                ? Math.min(Math.max(0, value), video.duration)
                : Math.max(0, value);
              return play(video);
            case 'speed':
              if (!isFinite(value) || value <= 0) return false;
              video.defaultPlaybackRate = value;
              video.playbackRate = value;
              return true;
            case 'volume':
              if (!isFinite(value)) return false;
              video.volume = Math.min(1, Math.max(0, value));
              video.muted = video.volume <= 0;
              return true;
          }
        } catch (e) { /* best effort */ }
        return false;
      }

      function forwardCommand(data) {
        var frames = document.querySelectorAll('iframe');
        for (var i = 0; i < frames.length; i++) {
          try { frames[i].contentWindow.postMessage(data, '*'); } catch (e) { /* detached */ }
        }
      }

      window.__aniliDispatchPlayerCommand = function(command, value) {
        var data = { __aniliPlayerCommand: 'v1', command: command, value: value };
        applyCommand(data);
        forwardCommand(data);
        return true;
      };
      window.addEventListener('message', function(event) {
        var data = event.data;
        if (!data || data.__aniliPlayerCommand !== 'v1') return;
        applyCommand(data);
        forwardCommand(data);
      }, true);

      function observe() {
        if (!document.documentElement) {
          setTimeout(observe, 0);
          return;
        }
        suppress(document);
        new MutationObserver(function(mutations) {
          installStyle();
          for (var i = 0; i < mutations.length; i++) {
            var mutation = mutations[i];
            if (mutation.type === 'attributes') suppressNativeControls(mutation.target);
            for (var j = 0; j < mutation.addedNodes.length; j++) {
              suppressNativeControls(mutation.addedNodes[j]);
            }
          }
        }).observe(document.documentElement, {
          childList: true,
          subtree: true,
          attributes: true,
          attributeFilter: ['controls']
        });
      }

      try { observe(); } catch (e) { /* best effort */ }

      // This bootstrap runs independently in every origin-matched frame. The Android JavaScript
      // bridge is exposed to those frames too, so a deeply nested cross-origin video can activate
      // the same native controls and progress UI as a top-level player.
      if (!window.__aniliProgressHooked) {
        window.__aniliProgressHooked = true;
        setInterval(function() {
          try {
            var video = findVideo();
            if (video && !window.__aniliVideoReported) {
              window.__aniliVideoReported = true;
              AniliProgress.onVideoAvailable();
            }
            if (video && isFinite(video.duration) && video.duration > 0 && video.currentTime >= 0) {
              AniliProgress.onTick(video.currentTime, video.duration, !video.paused, video.muted, video.volume);
            }
          } catch (e) { /* bridge detached or not available on an old WebView */ }
        }, 1000);
      }
    })();
""".trimIndent()

internal val PROVIDER_CHROME_ORIGIN_RULES = setOf("*")

private fun installWebPlayerBootstrap(webView: WebView) {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
        DiagnosticsLog.event("EmbedWebView document-start injection unavailable; using main-frame fallback")
        return
    }
    runCatching {
        WebViewCompat.addDocumentStartJavaScript(
            webView,
            HIDE_PROVIDER_CHROME_JS,
            PROVIDER_CHROME_ORIGIN_RULES,
        )
    }.onSuccess {
        DiagnosticsLog.event("EmbedWebView native-control bootstrap installed for all frames")
    }.onFailure {
        DiagnosticsLog.throwable("EmbedWebView provider chrome suppression install failed", it)
    }
}

private val REMOTE_TOGGLE_PLAYBACK_JS = """
    (function() {
      function toggle(root) {
        var selectors = [
          '[data-plyr="play"]', '.plyr__control--overlaid', '.vjs-big-play-button',
          '.jw-icon-playback', 'button[aria-label*="Play" i]',
          'button[aria-label*="Pause" i]', '.play-button'
        ];
        for (var i = 0; i < selectors.length; i++) {
          var button = root.querySelector(selectors[i]);
          if (button) { button.click(); return true; }
        }
        var video = root.querySelector('video');
        if (video) {
          if (video.paused) video.play(); else video.pause();
          return true;
        }
        return false;
      }
      try {
        if (toggle(document)) return true;
        var frames = document.querySelectorAll('iframe');
        for (var i = 0; i < frames.length; i++) {
          try {
            if (frames[i].contentDocument && toggle(frames[i].contentDocument)) return true;
          } catch (e) { /* cross-origin */ }
        }
      } catch (e) { /* ignored */ }
      return false;
    })();
""".trimIndent()

private fun RESUME_WHEN_READY_JS(targetSec: Double): String = """
    (function() {
      var attempts = 0;
      var timer = setInterval(function() {
        attempts++;
        try {
          var video = document.querySelector('video');
          if (video && video.readyState >= 1) {
            var target = $targetSec;
            video.currentTime = isFinite(video.duration) && video.duration > 0
              ? Math.min(target, video.duration)
              : target;
            video.play();
            clearInterval(timer);
          } else if (attempts >= 30) {
            clearInterval(timer);
          }
        } catch (e) {
          if (attempts >= 30) clearInterval(timer);
        }
      }, 250);
    })();
""".trimIndent()

private fun seekWebVideo(webView: WebView?, targetMs: Long?) {
    val targetSec = targetMs?.div(1000.0) ?: return
    webView?.let { dispatchWebPlayerCommand(it, "seek", targetSec, SEEK_VIDEO_JS(targetSec)) }
}

private fun setWebVolume(webView: WebView?, volume: Float, onChanged: (Float) -> Unit) {
    val target = volume.coerceIn(0f, 1f)
    webView?.let {
        dispatchWebPlayerCommand(it, "volume", target.toDouble(), WEB_VOLUME_JS(delta = null, absolute = target))
    }
    onChanged(target)
}

internal fun webPlayerCommandJs(command: String, value: Double? = null): String {
    require(command.matches(Regex("[A-Za-z]+"))) { "Invalid web player command" }
    val jsValue = value?.takeIf(Double::isFinite)?.toString() ?: "null"
    return "(function(){try{return window.__aniliDispatchPlayerCommand" +
        "?window.__aniliDispatchPlayerCommand('$command',$jsValue):false;}catch(e){return false;}})();"
}

private fun dispatchWebPlayerCommand(
    webView: WebView,
    command: String,
    value: Double? = null,
    fallbackScript: String? = null,
) {
    runCatching {
        webView.evaluateJavascript(webPlayerCommandJs(command, value)) { handled ->
            if (handled != "true" && fallbackScript != null) {
                runCatching { webView.evaluateJavascript(fallbackScript, null) }
            }
        }
    }
}

private fun WEB_VOLUME_JS(delta: Float?, absolute: Float?): String {
    val targetExpression = absolute?.coerceIn(0f, 1f)?.toString()
        ?: "Math.max(0, Math.min(1, v.volume + ${delta ?: 0f}))"
    return """
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
            if (!v) return -1;
            v.volume = $targetExpression;
            v.muted = v.volume <= 0;
            return v.muted ? 0 : v.volume;
          } catch (e) {
            return -1;
          }
        })();
    """.trimIndent()
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

private fun readDeviceVolume(audioManager: android.media.AudioManager?): Float {
    audioManager ?: return 1f
    val max = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
    if (max <= 0) return 1f
    return audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() / max
}

private fun applyDeviceVolume(audioManager: android.media.AudioManager?, value: Float) {
    audioManager ?: return
    val max = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
    if (max <= 0) return
    audioManager.setStreamVolume(
        android.media.AudioManager.STREAM_MUSIC,
        (value * max).toInt().coerceIn(0, max),
        0,
    )
}

private fun stopWebPlayback(webView: WebView) {
    DiagnosticsLog.event("EmbedWebView stop playback url=${webView.url ?: "none"}")
    dispatchWebPlayerCommand(webView, "pause", fallbackScript = PAUSE_VIDEO_JS)
    webView.onPause()
    webView.pauseTimers()
    webView.stopLoading()
    webView.loadUrl("about:blank")
}

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

private fun String?.hostOrNone(): String =
    this?.let { runCatching { Uri.parse(it).host }.getOrNull() } ?: "none"

private class WebProgressBridge(
    private val onTickCallback: (Double, Double, Boolean, Boolean, Double) -> Unit,
    private val onVideoAvailableCallback: () -> Unit,
) {
    @JavascriptInterface
    fun onTick(positionSec: Double, durationSec: Double, isPlaying: Boolean, muted: Boolean, volume: Double) {
        onTickCallback(positionSec, durationSec, isPlaying, muted, volume)
    }

    @JavascriptInterface
    fun onVideoAvailable() {
        onVideoAvailableCallback()
    }
}
