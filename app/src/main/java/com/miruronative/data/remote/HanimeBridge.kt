package com.miruronative.data.remote

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.miruronative.data.AppGraph
import com.miruronative.diagnostics.DiagnosticsLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Hidden hanime resolver.
 *
 * Two jobs, both of which need the site's own WebAssembly and so cannot be reimplemented here:
 *
 *  - **Credentials.** `vendor.js` computes a rotating signature in WASM and parks it on
 *    `window.ssignature` / `window.stime`. Those two headers are all the catalogue download needs,
 *    so that request is a plain OkHttp GET once we have read them.
 *  - **Streams.** The v11 player does not simply sign a request: it encrypts the handshake payload
 *    and decrypts the reply's `x-token`, both in WASM, through module-scoped functions that
 *    injected JS cannot reach. So rather than imitate the flow we let the real page perform it and
 *    take the HLS URL off the network, exactly as [FlixcloudBridge] does for flixcloud.
 */
@SuppressLint("StaticFieldLeak")
object HanimeBridge {
    private const val ORIGIN = "https://hanime.tv"
    private const val TAG = "HanimeBridge"

    /** `stime` is a timestamp and the signature expires with it; re-read well inside its life. */
    private const val CREDENTIAL_TTL_MS = 5 * 60 * 1000L

    private val main = Handler(Looper.getMainLooper())
    private val counter = AtomicLong(0)
    private val streamMutex = Mutex()
    private val credentialMutex = Mutex()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String?>>()

    @Volatile private var webView: WebView? = null
    @Volatile private var activeId: String? = null
    @Volatile private var cached: HanimeCredentials? = null

    data class HanimeCredentials(val signature: String, val time: String, val readAtMs: Long)

    @SuppressLint("SetJavaScriptEnabled")
    fun attach(wv: WebView) {
        DiagnosticsLog.event("$TAG.attach")
        webView = wv
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            userAgentString = userAgentString.replace("; wv", "")
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?,
            ): Boolean {
                DiagnosticsLog.event("$TAG blocked popup")
                return false
            }
        }
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val target = request?.url ?: return true
                if (!request.isForMainFrame) return false
                val host = target.host.orEmpty().lowercase()
                val allowed = target.scheme == "https" && (host == "hanime.tv" || host.endsWith(".hanime.tv"))
                if (!allowed) DiagnosticsLog.event("$TAG blocked nav host=$host")
                return !allowed
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // Stop the picture the moment the page has one. A muted video still decodes, and
                // on a TV stick that costs the episode being watched its hardware decoder.
                view?.evaluateJavascript(QUIET_VIDEOS_JS, null)
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                val url = request?.url?.toString().orEmpty()
                if (isHlsUrl(url)) {
                    val id = activeId
                    main.postDelayed({ complete(id, url) }, 250)
                }
                return null
            }

            @android.annotation.TargetApi(android.os.Build.VERSION_CODES.O)
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                DiagnosticsLog.event("$TAG render process gone didCrash=${detail?.didCrash()}")
                complete(activeId, null)
                if (webView === view) webView = null
                return true
            }
        }
    }

    fun detach(wv: WebView) {
        if (webView !== wv) return
        DiagnosticsLog.event("$TAG.detach")
        webView = null
        activeId = null
        pending.entries.toList().forEach { (id, deferred) ->
            if (pending.remove(id, deferred)) deferred.complete(null)
        }
    }

    /**
     * Signature headers for the catalogue download, re-read once the cached pair ages out. Loading
     * the home page is enough — `vendor.js` runs and sets the globals without any video involved.
     */
    suspend fun credentials(timeoutMs: Long = 20_000): HanimeCredentials? = credentialMutex.withLock {
        cached?.takeIf { SystemClock.elapsedRealtime() - it.readAtMs < CREDENTIAL_TTL_MS }?.let { return it }
        val wv = awaitWebView(timeoutMs) ?: return null
        loadOnMain(wv, ORIGIN)
        // The WASM publishes the pair a moment after the page settles, so poll rather than guess.
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val pair = withTimeoutOrNull(2_000) { readCredentials(wv) }
            if (pair != null) {
                DiagnosticsLog.event("$TAG credentials read time=${pair.time}")
                cached = pair
                main.post { if (activeId == null) wv.loadUrl("about:blank") }
                return pair
            }
            kotlinx.coroutines.delay(400)
        }
        DiagnosticsLog.event("$TAG credentials timeout")
        return null
    }

    /**
     * The HLS URL for one video, by letting hanime's own player negotiate it. Serialised because a
     * single hidden WebView cannot run two page loads at once.
     */
    suspend fun resolveStream(slug: String, timeoutMs: Long = 25_000): String? = streamMutex.withLock {
        val wv = awaitWebView(timeoutMs) ?: return null
        val id = counter.incrementAndGet().toString()
        val deferred = CompletableDeferred<String?>()
        pending[id] = deferred
        activeId = id
        val target = "$ORIGIN/videos/hentai/$slug"
        DiagnosticsLog.event("$TAG resolve slug=$slug")
        loadOnMain(wv, target)
        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
        pending.remove(id)
        if (activeId == id) activeId = null
        // Always park the page — a live hanime tab left open keeps timers and media alive.
        main.post { wv.loadUrl("about:blank") }
        if (result == null) DiagnosticsLog.event("$TAG resolve timeout slug=$slug")
        return result
    }

    private suspend fun readCredentials(wv: WebView): HanimeCredentials? {
        val deferred = CompletableDeferred<HanimeCredentials?>()
        main.post {
            wv.evaluateJavascript(READ_CREDENTIALS_JS) { raw ->
                val cleaned = raw?.trim()?.removeSurrounding("\"")?.replace("\\\"", "\"")
                val parts = cleaned?.split('|')?.takeIf { it.size == 2 && it.all(String::isNotBlank) }
                deferred.complete(
                    parts?.let { HanimeCredentials(it[0], it[1], SystemClock.elapsedRealtime()) },
                )
            }
        }
        return deferred.await()
    }

    private suspend fun awaitWebView(timeoutMs: Long): WebView? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            webView?.let { return it }
            kotlinx.coroutines.delay(100)
        }
        DiagnosticsLog.event("$TAG no WebView attached; is adult content enabled?")
        return null
    }

    private fun loadOnMain(wv: WebView, url: String) = main.post {
        wv.stopLoading()
        wv.loadUrl(url)
    }

    private fun complete(id: String?, url: String?) {
        val actualId = id ?: return
        if (pending.remove(actualId)?.complete(url) == true && url != null) {
            DiagnosticsLog.event("$TAG resolved host=${url.hostOrNone()}")
        }
    }

    private fun isHlsUrl(url: String): Boolean = url.contains(".m3u8", ignoreCase = true)

    private fun String?.hostOrNone(): String =
        this?.let { runCatching { Uri.parse(it).host }.getOrNull() } ?: "none"

    /** Read the pair the WASM parks on `window`; empty until it has run. */
    private val READ_CREDENTIALS_JS = """
        (function() {
          try {
            var s = window.ssignature, t = window.stime;
            return (s && t) ? (String(s) + '|' + String(t)) : '';
          } catch (e) { return ''; }
        })();
    """.trimIndent()

    /**
     * Nothing here needs a decoded frame — the URL is taken off the network — so keep the page
     * running and the picture stopped. Muting alone does not stop a decoder.
     */
    private val QUIET_VIDEOS_JS = """
        (function() {
          if (window.__aniliHanimeQuiet) return;
          window.__aniliHanimeQuiet = true;
          function quiet() {
            try {
              var videos = document.querySelectorAll('video');
              for (var i = 0; i < videos.length; i++) {
                var video = videos[i];
                video.muted = true;
                video.autoplay = false;
                if (!video.paused) video.pause();
              }
            } catch (e) {}
          }
          quiet();
          setInterval(quiet, 100);
        })();
    """.trimIndent()
}
