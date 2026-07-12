package com.miruronative.ui.watch

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient.CustomViewCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.miruronative.diagnostics.CrashReporter
import com.miruronative.ui.adaptive.LocalAppDeviceProfile

/**
 * Renders an embed/iframe provider (or the live site) in a WebView. This is both the player for
 * `type:"embed"` sources and the durable fallback when the native path is Cloudflare-blocked.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EmbedWebView(
    url: String,
    referer: String?,
    modifier: Modifier = Modifier,
    onFullscreenChanged: (Boolean) -> Unit = {},
) {
    val device = LocalAppDeviceProfile.current
    var customView by remember { mutableStateOf<View?>(null) }
    var customViewCallback by remember { mutableStateOf<CustomViewCallback?>(null) }
    val currentOnFullscreenChanged by rememberUpdatedState(onFullscreenChanged)

    val chromeClient = remember {
        object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null) {
                    callback?.onCustomViewHidden()
                    return
                }
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }

                customView = view
                customViewCallback = callback
                currentOnFullscreenChanged(true)
            }

            override fun onHideCustomView() {
                val fullscreenView = customView ?: return
                (fullscreenView.parent as? ViewGroup)?.removeView(fullscreenView)
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                currentOnFullscreenChanged(false)
            }
        }
    }

    BackHandler(enabled = customView != null) { chromeClient.onHideCustomView() }

    DisposableEffect(chromeClient) {
        onDispose {
            val fullscreenView = customView
            if (fullscreenView != null) {
                (fullscreenView.parent as? ViewGroup)?.removeView(fullscreenView)
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
            }
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
                            userAgentString = userAgentString.replace("; wv", "") // look less like a webview
                        }
                        webViewClient = WebViewClient()
                        webChromeClient = chromeClient
                        if (device.isTv) post { requestFocus() }
                    }
                } catch (e: Throwable) {
                    CrashReporter.logNonFatal("System WebView unavailable; embed player disabled", e)
                    View(ctx).apply { setBackgroundColor(android.graphics.Color.BLACK) }
                }
            },
            update = { view ->
                val web = view as? WebView ?: return@AndroidView
                val headers = referer?.let { mapOf("Referer" to it) } ?: emptyMap()
                if (web.url != url) web.loadUrl(url, headers)
            },
            onRelease = { view ->
                val web = view as? WebView ?: return@AndroidView
                web.stopLoading()
                web.loadUrl("about:blank")
                web.clearHistory()
                web.removeAllViews()
                web.webChromeClient = null
                web.webViewClient = WebViewClient()
                web.destroy()
            },
        )

        customView?.let { fullscreenView ->
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    FrameLayout(ctx).apply {
                        setBackgroundColor(android.graphics.Color.BLACK)
                        isFocusable = true
                        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                    }
                },
                update = { container ->
                    if (fullscreenView.parent !== container) {
                        (fullscreenView.parent as? ViewGroup)?.removeView(fullscreenView)
                        container.removeAllViews()
                        container.addView(
                            fullscreenView,
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            ),
                        )
                    }
                },
                onRelease = { container -> container.removeAllViews() },
            )
        }
    }
}
