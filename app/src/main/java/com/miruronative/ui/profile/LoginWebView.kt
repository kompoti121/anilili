package com.miruronative.ui.profile

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.miruronative.data.auth.AuthManager
import com.miruronative.diagnostics.CrashReporter
import com.miruronative.ui.adaptive.LocalAppDeviceProfile
import com.miruronative.ui.adaptive.focusHighlight
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * Fullscreen AniList login. Loads the implicit-grant authorize URL; when AniList redirects to
 * `http://localhost/#access_token=…`, we grab the token before loading that URL.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginWebView(onToken: (String) -> Unit, onCancel: () -> Unit) {
    val device = LocalAppDeviceProfile.current
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    try {
                        var tokenHandled = false
                        WebView(ctx).apply {
                            isFocusable = true
                            isFocusableInTouchMode = true
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            webViewClient = object : WebViewClient() {
                                private fun handleRedirect(view: WebView?, url: String?): Boolean {
                                    if (tokenHandled || url == null || !AuthManager.isRedirect(url)) return false
                                    val token = AuthManager.extractToken(url) ?: return false
                                    tokenHandled = true
                                    view?.stopLoading()
                                    onToken(token)
                                    return true
                                }

                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    handleRedirect(view, url)
                                }

                                @Suppress("DEPRECATION")
                                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                    return handleRedirect(view, url)
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean {
                                    return handleRedirect(view, request?.url?.toString())
                                }
                            }
                            loadUrl(AuthManager.AUTHORIZE_URL)
                            if (device.isTv) post { requestFocus() }
                        }
                    } catch (e: Throwable) {
                        CrashReporter.logNonFatal("System WebView unavailable; AniList login disabled", e)
                        android.view.View(ctx)
                    }
                },
                onRelease = { view ->
                    val web = view as? WebView ?: return@AndroidView
                    web.stopLoading()
                    web.loadUrl("about:blank")
                    web.clearHistory()
                    web.removeAllViews()
                    web.destroy()
                },
            )
            IconButton(
                onClick = onCancel,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(device.pagePadding)
                    .focusHighlight(RoundedCornerShape(24.dp)),
            ) {
                Icon(Icons.Default.Close, contentDescription = "Cancel")
            }
        }
    }
}
