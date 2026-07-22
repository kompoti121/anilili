package com.miruronative.data.remote

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
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
 * Hidden flixcloud resolver. The embed owns rotating JS/WASM decryption, so we let a real WebView
 * run it and capture the final HLS URL when the page gives it to hls.js or requests it.
 */
@SuppressLint("StaticFieldLeak")
object FlixcloudBridge {
    private const val ORIGIN = "https://flixcloud.cc"
    private const val TAG = "FlixcloudBridge"

    private val main = Handler(Looper.getMainLooper())
    private val counter = AtomicLong(0)
    private val mutex = Mutex()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<FlixcloudResolvedStream?>>()

    @Volatile private var webView: WebView? = null
    @Volatile private var activeId: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun attach(wv: WebView) {
        DiagnosticsLog.event("$TAG.attach")
        webView = wv
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            userAgentString = userAgentString.replace("; wv", "")
        }
        wv.addJavascriptInterface(Bridge, "AndroidFlixcloud")
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
                val allowed = target.scheme == "https" && (host == "flixcloud.cc" || host.endsWith(".flixcloud.cc"))
                if (!allowed) DiagnosticsLog.event("$TAG blocked nav host=$host")
                return !allowed
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val id = activeId ?: return
                DiagnosticsLog.event("$TAG page finished host=${url.hostOrNone()}")
                view?.evaluateJavascript(captureScript(id), null)
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                val url = request?.url?.toString().orEmpty()
                if (isHlsUrl(url)) {
                    val id = activeId
                    main.postDelayed({ complete(id, url, "request") }, 250)
                }
                return null
            }

            @android.annotation.TargetApi(android.os.Build.VERSION_CODES.M)
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    DiagnosticsLog.event("$TAG main-frame error code=${error?.errorCode} description=${error?.description}")
                }
            }

            @android.annotation.TargetApi(android.os.Build.VERSION_CODES.O)
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                DiagnosticsLog.event("$TAG render process gone didCrash=${detail?.didCrash()}")
                complete(activeId, null, "render_gone")
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
        wv.removeJavascriptInterface("AndroidFlixcloud")
    }

    suspend fun resolve(
        embedUrl: String,
        referer: String?,
        // A resolve that is going nowhere still holds the hidden page open until it expires. TV
        // sticks give up sooner and fall back to the embed rather than keep a second player
        // parked alongside playback.
        timeoutMs: Long = if (AppGraph.isTv) 5_000 else 12_000,
    ): FlixcloudResolvedStream? = mutex.withLock {
        val id = counter.incrementAndGet().toString()
        val deferred = CompletableDeferred<FlixcloudResolvedStream?>()
        pending[id] = deferred
        activeId = id
        val target = captureUrl(embedUrl)
        loadWhenAttached(id, target, referer, SystemClock.elapsedRealtime() + timeoutMs)
        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
        if (!deferred.isCompleted) {
            pending.remove(id)
            DiagnosticsLog.event("$TAG timeout host=${target.hostOrNone()}")
            main.post { if (activeId == id) webView?.loadUrl("about:blank") }
        }
        if (activeId == id) activeId = null
        result?.takeIf { isHlsUrl(it.url) }
    }

    private fun loadWhenAttached(id: String, target: String, referer: String?, deadlineMs: Long) {
        main.post(object : Runnable {
            override fun run() {
                if (activeId != id || !pending.containsKey(id)) return
                val wv = webView
                if (wv != null) {
                    DiagnosticsLog.event("$TAG load host=${target.hostOrNone()}")
                    wv.stopLoading()
                    wv.loadUrl(target, referer?.let { mapOf("Referer" to it) } ?: emptyMap())
                    return
                }
                if (SystemClock.elapsedRealtime() >= deadlineMs) {
                    complete(id, null, "not_attached_timeout")
                    return
                }
                main.postDelayed(this, 50)
            }
        })
    }

    object Bridge {
        @JavascriptInterface
        fun onResolved(id: String, url: String, playlistKey: String) {
            if (isHlsUrl(url)) complete(id, url, "js", playlistKey.ifBlank { null })
        }
    }

    private fun complete(id: String?, url: String?, source: String, playlistKey: String? = null) {
        val actualId = id ?: return
        pending.remove(actualId)?.complete(url?.let { FlixcloudResolvedStream(it, playlistKey) })
        if (url != null) {
            DiagnosticsLog.event(
                "$TAG resolved source=$source host=${url.hostOrNone()} playlistKey=${playlistKey != null}",
            )
            main.post { if (activeId == actualId) webView?.loadUrl("about:blank") }
        }
    }

    private fun captureUrl(embedUrl: String): String {
        val uri = Uri.parse(embedUrl)
        val builder = uri.buildUpon()
        if (uri.getQueryParameter("autoPlay") == null) builder.appendQueryParameter("autoPlay", "true")
        if (uri.getQueryParameter("skI") == null) builder.appendQueryParameter("skI", "false")
        if (uri.getQueryParameter("skO") == null) builder.appendQueryParameter("skO", "false")
        if (uri.getQueryParameter("kuudere_ts") == null) builder.appendQueryParameter("kuudere_ts", System.currentTimeMillis().toString())
        return builder.build().toString()
    }

    private fun captureScript(id: String): String = """
        (function() {
          if (window.__aniliFlixHooked) return;
          window.__aniliFlixHooked = true;
          function report(value) {
            try {
              var url = String(value || '');
               if (url.indexOf('.m3u8') >= 0) {
                 AndroidFlixcloud.onResolved('$id', url, String(window.__pk || ''));
               }
            } catch (e) {}
          }
          // The page is opened with autoPlay so it runs its own decrypt flow, but nothing here
          // needs a decoded frame: the URL is captured from loadSource/fetch/XHR. Leaving the
          // video playing costs a hardware decoder instance, and a TV stick only has one to give
          // — the system then preempts the episode the viewer is actually watching. Keep the page
          // running and the picture stopped.
          function quietVideos() {
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
          function hookHls() {
            try {
              if (window.Hls && window.Hls.prototype && !window.Hls.prototype.__aniliFlixHooked) {
                var original = window.Hls.prototype.loadSource;
                window.Hls.prototype.__aniliFlixHooked = true;
                window.Hls.prototype.loadSource = function(source) {
                  report(source);
                  return original.apply(this, arguments);
                };
              }
            } catch (e) {}
          }
          try {
            var originalFetch = window.fetch;
            if (originalFetch && !originalFetch.__aniliFlixHooked) {
              var wrappedFetch = function(input, init) {
                report(typeof input === 'string' ? input : input && input.url);
                return originalFetch.apply(this, arguments).then(function(response) {
                  report(response && response.url);
                  return response;
                });
              };
              wrappedFetch.__aniliFlixHooked = true;
              window.fetch = wrappedFetch;
            }
          } catch (e) {}
          try {
            var originalOpen = XMLHttpRequest.prototype.open;
            if (originalOpen && !originalOpen.__aniliFlixHooked) {
              XMLHttpRequest.prototype.open = function(method, url) {
                report(url);
                return originalOpen.apply(this, arguments);
              };
              XMLHttpRequest.prototype.open.__aniliFlixHooked = true;
            }
          } catch (e) {}
          hookHls();
          quietVideos();
          setInterval(function() { hookHls(); quietVideos(); }, 100);
        })();
    """.trimIndent()

    private fun isHlsUrl(url: String): Boolean =
        url.contains(".m3u8", ignoreCase = true)

    private fun String?.hostOrNone(): String =
        this?.let { runCatching { Uri.parse(it).host }.getOrNull() } ?: "none"
}

data class FlixcloudResolvedStream(val url: String, val playlistKey: String?)
