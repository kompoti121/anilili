package com.miruronative.data.remote

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.miruronative.diagnostics.DiagnosticsLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Raw pipe response as returned by the in-page fetch, before deobfuscation. */
data class RawPipeResponse(val ok: Boolean, val status: Int, val obf: String?, val body: String?, val error: String?)

/**
 * Routes pipe requests through a real (hidden) WebView. Cloudflare fingerprints the HTTP client,
 * so a plain OkHttp call gets a 403 WAF block; a same-origin `fetch()` from inside a loaded
 * miruro.to page rides the browser's TLS fingerprint + `cf_clearance` cookie and is allowed.
 *
 * The WebView is created and attached to the window by [com.miruronative.ui.PipeWebView]; this
 * object owns the JS bridge and the request/response plumbing.
 */
@SuppressLint("StaticFieldLeak")
object PipeBridge {
    /**
     * Mirror domains, tried in order. ISPs block these piecemeal (user logs show .to timing out
     * on one network while others resolve), so a main-frame load failure rolls to the next
     * mirror instead of taking every pipe request down with it.
     */
    private val ORIGINS = listOf(
        "https://www.miruro.to",
        "https://www.miruro.tv",
        "https://www.miruro.bz",
    )

    private val main = Handler(Looper.getMainLooper())

    /**
     * The hidden tab hosts the full miruro SPA, whose scripts/animations otherwise run for the
     * whole session — on a Fire TV stick its compositor can exhaust Chromium's tile budget and
     * leave the visible episode black while audio continues. Idle (View.onPause) the WebView
     * shortly after the last pipe request completes; each fetch resumes it first. The CF
     * session/cookies survive onPause just fine.
     */
    private const val IDLE_AFTER_MS = 2_000L
    private val idleRunnable = Runnable {
        webView?.onPause()
        DiagnosticsLog.event("PipeBridge webview idled")
    }

    private fun scheduleIdle() {
        main.removeCallbacks(idleRunnable)
        main.postDelayed(idleRunnable, IDLE_AFTER_MS)
    }

    private fun scheduleIdleIfUnused() {
        main.post {
            if (pending.isEmpty()) scheduleIdle()
        }
    }

    @Volatile private var webView: WebView? = null
    @Volatile private var ready = CompletableDeferred<Boolean>()
    @Volatile private var originIndex = 0
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val counter = AtomicLong(0)

    private val activeOrigin: String get() = ORIGINS[originIndex]

    /** Called from the hosting Composable on the main thread with a freshly created WebView. */
    @SuppressLint("SetJavaScriptEnabled")
    fun attach(wv: WebView) {
        DiagnosticsLog.event("PipeBridge.attach")
        webView = wv
        if (ready.isCompleted) ready = CompletableDeferred()

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
        }
        wv.addJavascriptInterface(Bridge, "AndroidPipe")
        wv.webViewClient = object : WebViewClient() {
            // Pin the tab to Miruro's mirrors — block ad popunders / intent: redirects that
            // would navigate away and kill the same-origin pipe session.
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return true
                val host = url.host.orEmpty().lowercase()
                val allowed = url.scheme == "https" && ORIGINS.any { origin ->
                    val originHost = origin.removePrefix("https://www.")
                    host == originHost || host.endsWith(".$originHost")
                }
                if (!allowed) {
                    DiagnosticsLog.event("PipeBridge blocked nav: $url")
                    Log.d(TAG, "blocked nav: $url")
                }
                return !allowed
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                DiagnosticsLog.event("PipeBridge page started: ${url ?: "unknown"}")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                DiagnosticsLog.event("PipeBridge page finished: ${url ?: "unknown"} title=${view?.title ?: "none"}")
                Log.d(TAG, "onPageFinished: $url  title=${view?.title}")
                // Give Cloudflare a moment to settle, then allow fetches.
                if (url != null && url.startsWith(activeOrigin)) {
                    main.postDelayed(
                        {
                            if (!ready.isCompleted) ready.complete(true)
                            scheduleIdle()
                        },
                        2000,
                    )
                }
            }

            @android.annotation.TargetApi(android.os.Build.VERSION_CODES.M)
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                if (request?.isForMainFrame != true) return
                DiagnosticsLog.event(
                    "PipeBridge main-frame error code=${error?.errorCode} " +
                        "description=${error?.description} origin=$activeOrigin",
                )
                // This mirror is unreachable (ISP block, DNS, site down): roll to the next one.
                // Only once all mirrors fail do waiters unblock into the cache/error path.
                if (originIndex < ORIGINS.lastIndex) {
                    originIndex++
                    DiagnosticsLog.event("PipeBridge trying mirror $activeOrigin")
                    main.post { webView?.loadUrl("$activeOrigin/") }
                } else if (!ready.isCompleted) {
                    ready.complete(false)
                }
            }

            @android.annotation.TargetApi(android.os.Build.VERSION_CODES.O)
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                DiagnosticsLog.event(
                    "PipeBridge render process gone didCrash=${detail?.didCrash()} " +
                        "priority=${detail?.rendererPriorityAtExit()}",
                )
                view?.let(::detach)
                return true
            }
        }
        DiagnosticsLog.event("PipeBridge load origin=$activeOrigin")
        wv.loadUrl("$activeOrigin/")
    }

    /** Releases the attached browser and fails requests that can no longer complete. */
    fun detach(wv: WebView) {
        if (webView !== wv) return
        DiagnosticsLog.event("PipeBridge.detach")
        main.removeCallbacks(idleRunnable)
        webView = null
        ready = CompletableDeferred()
        pending.entries.toList().forEach { (id, request) ->
            if (pending.remove(id, request)) {
                request.complete("""{"ok":false,"status":-1,"error":"webview released"}""")
            }
        }
        wv.removeJavascriptInterface("AndroidPipe")
    }

    object Bridge {
        @JavascriptInterface
        fun onResult(id: String, json: String) {
            pending.remove(id)?.complete(json)
        }
    }

    /** Runs `fetch('/api/secure/pipe?e=…')` inside the page and returns the raw JSON bridge payload. */
    suspend fun fetch(e: String, timeoutMs: Long = 30_000): String {
        withTimeoutOrNull(25_000) { ready.await() }
        val id = counter.incrementAndGet().toString()
        val deferred = CompletableDeferred<String>()
        pending[id] = deferred

        val js = """
            (function(){
              try {
                fetch('/api/secure/pipe?e=$e', { headers: { 'Accept': '*/*' }, credentials: 'include' })
                  .then(function(r){
                    return r.text().then(function(b){
                      AndroidPipe.onResult('$id', JSON.stringify({
                        ok: r.ok, status: r.status,
                        obf: (r.headers.get('x-obfuscated') || ''), body: b
                      }));
                    });
                  })
                  .catch(function(err){
                    AndroidPipe.onResult('$id', JSON.stringify({ ok:false, status:-1, error:String(err) }));
                  });
              } catch (err) {
                AndroidPipe.onResult('$id', JSON.stringify({ ok:false, status:-1, error:String(err) }));
              }
            })();
        """.trimIndent()

        main.post {
            val wv = webView
            if (wv == null) {
                pending.remove(id)?.complete("""{"ok":false,"status":-1,"error":"webview not ready"}""")
            } else {
                main.removeCallbacks(idleRunnable)
                wv.onResume()
                wv.evaluateJavascript(js, null)
            }
        }

        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
            ?: run {
                pending.remove(id)
                DiagnosticsLog.event("PipeBridge fetch timeout e.len=${e.length}")
                """{"ok":false,"status":-1,"error":"timeout"}"""
            }
        scheduleIdleIfUnused()
        Log.d(TAG, "fetch(e.len=${e.length}) -> ${result.take(180)}")
        return result
    }

    private const val TAG = "PipeBridge"
}
