package com.miruronative.ui

import android.webkit.WebView
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.size
import com.miruronative.data.remote.PipeBridge

/**
 * Hidden 1dp WebView that hosts the Cloudflare-cleared miruro.to session. It stays attached to the
 * window (so Chromium treats it like a real tab) but is effectively invisible. All pipe requests
 * run as same-origin fetches inside it — see [PipeBridge].
 */
@Composable
fun PipeWebView() {
    AndroidView(
        factory = { ctx ->
            WebView(ctx).also {
                it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                it.isFocusable = false
                it.isClickable = false
                PipeBridge.attach(it)
            }
        },
        onRelease = { web ->
            PipeBridge.detach(web)
            web.stopLoading()
            web.webChromeClient = null
            web.webViewClient = android.webkit.WebViewClient()
            web.destroy()
        },
        modifier = Modifier.size(1.dp),
    )
}
