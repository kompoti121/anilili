package com.miruronative.data.remote

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.miruronative.MainActivity
import com.miruronative.data.model.StreamItem
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device live smoke test for the four iframe players captured for Yomi no Tsugai episode 15.
 * This is deliberately an instrumentation test: an HTTP 200 cannot prove that player JavaScript
 * initializes in Android WebView.
 */
@RunWith(AndroidJUnit4::class)
class AllAnimeWebViewTest {
    @Test
    fun currentReachableIframeSourcesInitializeInWebViewWithPlayerReferer() {
        val result = liveYomiSources()
        val expected = listOf("Mp4", "Uni", "Fm-Hls")
        val embeds = expected.associateWith { name ->
            result.firstOrNull { item -> item.isEmbed && item.label.equals(name, true) }
                ?: error("Missing live iframe source $name")
        }
        assertTrue(embeds.values.all { it.referer == AllAnimeProtocolConfig.active.playerReferer })

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val probes = embeds.mapValues { (_, stream) -> loadPlayer(scenario, stream) }
            val failures = probes.mapNotNull { (name, probe) -> probe.failure(name) }
            assertTrue(failures.joinToString("\n"), failures.isEmpty())
        }
    }

    @Test
    fun okRuInitializesWhenHostIsReachable() {
        val stream = liveYomiSources().firstOrNull { it.isEmbed && it.label.equals("Ok", true) }
            ?: error("Missing live iframe source Ok")
        assertEquals(AllAnimeProtocolConfig.active.playerReferer, stream.referer)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val probe = loadPlayer(scenario, stream)
            assumeTrue(
                "OK.ru did not return iframe resources on this network: ${probe.resources}",
                probe.hostResourceCount >= 2,
            )
            val failure = probe.failure("Ok")
            assertTrue(failure.orEmpty(), failure == null)
        }
    }

    private fun liveYomiSources(): List<StreamItem> {
        val provider = AllAnimeProvider(
            OkHttpClient.Builder()
                .followRedirects(true)
                .callTimeout(Duration.ofSeconds(30))
                .build(),
            Json { ignoreUnknownKeys = true },
        )
        val result = provider.sourcesForShow(
            showId = "wy7W7QxqEJAgr8h5z",
            audio = "sub",
            episode = 15,
            allowLegacyFallback = false,
        )
        assertEquals(AllAnimeProvider.SourceRoute.CURRENT, provider.lastSourceRoute)
        return result.streams
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun loadPlayer(
        scenario: ActivityScenario<MainActivity>,
        stream: StreamItem,
    ): PlayerProbe {
        val pageLatch = CountDownLatch(1)
        var pageFinished = false
        var error: String? = null
        val diagnostics = mutableListOf<String>()
        val resourceUrls = mutableListOf<String>()
        lateinit var webView: WebView

        scenario.onActivity { activity ->
            webView = WebView(activity).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.setSupportMultipleWindows(true)
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.userAgentString = settings.userAgentString.replace("; wv", "")
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                        synchronized(diagnostics) {
                            if (diagnostics.size < 30) {
                                diagnostics += "console:${consoleMessage.messageLevel()}:${consoleMessage.message()}"
                            }
                        }
                        return true
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        synchronized(resourceUrls) {
                            if (resourceUrls.size < 100) resourceUrls += request.url.toString()
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        pageFinished = false
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        pageFinished = true
                        pageLatch.countDown()
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        resourceError: WebResourceError,
                    ) {
                        if (request.isForMainFrame) {
                            error = "${resourceError.errorCode} ${resourceError.description}"
                            pageLatch.countDown()
                        } else synchronized(diagnostics) {
                            if (diagnostics.size < 30) {
                                diagnostics += "resource:${resourceError.errorCode}:${request.url.host}"
                            }
                        }
                    }

                    override fun onReceivedHttpError(
                        view: WebView,
                        request: WebResourceRequest,
                        errorResponse: WebResourceResponse,
                    ) {
                        if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                            error = "HTTP ${errorResponse.statusCode}"
                            pageLatch.countDown()
                        } else if (errorResponse.statusCode >= 400) synchronized(diagnostics) {
                            if (diagnostics.size < 30) {
                                diagnostics += "http:${errorResponse.statusCode}:${request.url.host}${request.url.path}"
                            }
                        }
                    }
                }
            }
            activity.setContentView(webView)
            if (Uri.parse(stream.url).host.equals("ok.ru", true)) {
                webView.loadDataWithBaseURL(
                    requireNotNull(stream.referer),
                    iframeShell(stream.url),
                    "text/html",
                    "UTF-8",
                    null,
                )
            } else {
                webView.loadUrl(stream.url, mapOf("Referer" to requireNotNull(stream.referer)))
            }
        }

        pageLatch.await(30, TimeUnit.SECONDS)
        var playerState = "not evaluated"
        var initialized = false
        repeat(25) {
            if (error != null || initialized) return@repeat
            val jsLatch = CountDownLatch(1)
            scenario.onActivity {
                webView.evaluateJavascript(PLAYER_PROBE_JS) { value ->
                    playerState = value.orEmpty()
                    initialized = value.contains("video-ready") || value.contains("player-markup")
                    if (Uri.parse(stream.url).host.equals("ok.ru", true)) {
                        initialized = initialized && synchronized(resourceUrls) {
                            resourceUrls.count { Uri.parse(it).host.equals("ok.ru", true) } >= 2
                        }
                    }
                    jsLatch.countDown()
                }
            }
            jsLatch.await(2, TimeUnit.SECONDS)
            if (!initialized) Thread.sleep(1_000L)
        }
        scenario.onActivity { webView.destroy() }
        return PlayerProbe(
            pageFinished,
            initialized,
            playerState,
            error,
            synchronized(diagnostics) { diagnostics.joinToString(" | ") },
            synchronized(resourceUrls) { resourceUrls.takeLast(20).joinToString(" | ") },
            synchronized(resourceUrls) {
                val streamHost = Uri.parse(stream.url).host
                resourceUrls.count { Uri.parse(it).host.equals(streamHost, true) }
            },
        )
    }

    private data class PlayerProbe(
        val pageFinished: Boolean,
        val playerInitialized: Boolean,
        val playerState: String,
        val error: String?,
        val diagnostics: String,
        val resources: String,
        val hostResourceCount: Int,
    ) {
        fun failure(name: String): String? = when {
            error != null -> "$name main-frame load failed: $error"
            !playerInitialized ->
                "$name player did not initialize: $playerState; diagnostics=$diagnostics; resources=$resources"
            else -> null
        }
    }

    companion object {
        private fun iframeShell(url: String): String =
            """<!doctype html><html><body style="margin:0;background:#000"><iframe src="$url" allow="autoplay; fullscreen; encrypted-media; picture-in-picture" style="position:fixed;inset:0;width:100%;height:100%;border:0"></iframe></body></html>"""

        private val PLAYER_PROBE_JS = """
            (() => {
              const video = document.querySelector('video');
              if (video) {
                video.muted = true;
                const source = video.querySelector('source[src]');
                if (!video.src && source?.src) video.src = source.src;
                video.load();
                video.play().catch(() => {});
                if (video.readyState >= 1 || video.currentSrc || video.src) {
                  return 'video-ready:' + video.readyState + ':paused=' + video.paused;
                }
                video.dispatchEvent(new MouseEvent('click', {bubbles:true, cancelable:true}));
                const play = document.querySelector('.vjs-big-play-button,.jw-icon-playback,.plyr__control--overlaid,.play-button,[aria-label*="play" i]');
                play?.dispatchEvent(new MouseEvent('click', {bubbles:true, cancelable:true}));
                document.elementFromPoint(innerWidth / 2, innerHeight / 2)?.dispatchEvent(
                  new MouseEvent('click', {bubbles:true, cancelable:true, view:window})
                );
                return 'video-waiting:ready=' + video.readyState + ':title=' + document.title +
                  ':body=' + (document.body?.innerText?.length || 0);
              }
              const player = document.querySelector('.jwplayer,.plyr,.video-js,[class*="player"],iframe[src]');
              if (player) {
                player.dispatchEvent(new MouseEvent('click', {bubbles:true, cancelable:true}));
                return 'player-markup:' + player.tagName;
              }
              document.body?.dispatchEvent(new MouseEvent('click', {bubbles:true, cancelable:true}));
              return 'no-player:' + document.readyState + ':body=' + (document.body?.innerText?.length || 0);
            })()
        """.trimIndent()
    }
}
