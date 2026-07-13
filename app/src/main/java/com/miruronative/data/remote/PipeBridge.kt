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
    const val ORIGIN = "https://www.miruro.to"

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var webView: WebView? = null
    @Volatile private var ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val counter = AtomicLong(0)

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
            // Pin the tab to miruro.to — block ad popunders / intent: redirects that would
            // navigate away and kill the same-origin pipe session.
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return true
                val host = url.host.orEmpty().lowercase()
                val allowed = url.scheme == "https" &&
                    (host == "miruro.to" || host.endsWith(".miruro.to"))
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
                if (url != null && url.startsWith(ORIGIN)) {
                    main.postDelayed({ if (!ready.isCompleted) ready.complete(true) }, 2000)
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    DiagnosticsLog.event(
                        "PipeBridge main-frame error code=${error?.errorCode} " +
                            "description=${error?.description}",
                    )
                }
                // Main-frame load failed (offline, DNS, site down): unblock waiters so pipe
                // requests fail fast to the cache/error path instead of stalling 25 s each.
                if (request?.isForMainFrame == true && !ready.isCompleted) ready.complete(false)
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                DiagnosticsLog.event(
                    "PipeBridge render process gone didCrash=${detail?.didCrash()} " +
                        "priority=${detail?.rendererPriorityAtExit()}",
                )
                view?.let(::detach)
                return true
            }
        }
        DiagnosticsLog.event("PipeBridge load origin=$ORIGIN")
        wv.loadUrl("$ORIGIN/")
    }

    /** Releases the attached browser and fails requests that can no longer complete. */
    fun detach(wv: WebView) {
        if (webView !== wv) return
        DiagnosticsLog.event("PipeBridge.detach")
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
                wv.evaluateJavascript(js, null)
            }
        }

        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
            ?: run {
                pending.remove(id)
                DiagnosticsLog.event("PipeBridge fetch timeout e.len=${e.length}")
                """{"ok":false,"status":-1,"error":"timeout"}"""
            }
        Log.d(TAG, "fetch(e.len=${e.length}) -> ${result.take(180)}")
        return result
    }

    private const val TAG = "PipeBridge"
}
