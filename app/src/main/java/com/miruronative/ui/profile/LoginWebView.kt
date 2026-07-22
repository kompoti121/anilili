package com.miruronative.ui.profile

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.miruronative.diagnostics.CrashReporter
import com.miruronative.diagnostics.DiagnosticsLog
import com.miruronative.ui.adaptive.LocalAppDeviceProfile
import com.miruronative.ui.adaptive.focusHighlight
import org.json.JSONObject

/**
 * Fullscreen OAuth login shared by AniList and MyAnimeList. Loads [authorizeUrl]; when the
 * service redirects to its registered localhost URL, [extractResult] pulls the token (AniList
 * implicit grant) or authorization code (MAL) out of it before Android networking tries to
 * open localhost, and the result is handed to [onResult].
 */

/**
 * Android TV's system IME is unreliable for WebView inputs: it can appear while D-pad events keep
 * moving focus around the page, then dismiss as soon as the HTML input blurs. This script reports
 * editable focus to a native Compose editor. The editor owns D-pad/IME focus and mirrors its value
 * back into the original first-party OAuth field. It is injected after every full navigation.
 */
private const val TV_EDITOR_JS = """
(function() {
  if (window.__miruroTvEditorInstalled) return;
  window.__miruroTvEditorInstalled = true;
  window.__miruroTvEditorSequence = 0;
  function editable(el) {
    if (!el) return false;
    if (el.isContentEditable) return true;
    var tag = (el.tagName || '').toLowerCase();
    if (tag === 'textarea') return true;
    if (tag !== 'input') return false;
    var t = (el.type || 'text').toLowerCase();
    return ['hidden','button','checkbox','radio','submit','reset','file','image','range','color'].indexOf(t) < 0;
  }
  function editables() {
    return Array.prototype.filter.call(
      document.querySelectorAll('input,textarea,[contenteditable="true"]'),
      function(el) { return editable(el) && !el.disabled && !el.readOnly; }
    );
  }
  document.addEventListener('focusin', function(e) {
    var el = e.target;
    if (!editable(el)) return;
    if (!el.dataset.miruroTvEditorId) {
      el.dataset.miruroTvEditorId = String(++window.__miruroTvEditorSequence);
    }
    var fields = editables();
    var index = fields.indexOf(el);
    var type = (el.type || (el.isContentEditable ? 'text' : el.tagName) || 'text').toLowerCase();
    var label = el.getAttribute('aria-label') || el.placeholder || el.name ||
      (type === 'password' ? 'Password' : 'Text field');
    MiruroTvEditor.onEditableFocused(
      el.dataset.miruroTvEditorId,
      label,
      type,
      el.value || el.textContent || '',
      index >= 0 && index < fields.length - 1
    );
  }, true);
})();
"""

private class TvEditorBridge(
    private val webView: WebView,
    private val onFocused: (TvWebField) -> Unit,
) {
    @JavascriptInterface
    fun onEditableFocused(
        id: String,
        label: String,
        type: String,
        value: String,
        hasNext: Boolean,
    ) {
        webView.post {
            if (!webView.isAttachedToWindow) return@post
            DiagnosticsLog.event("TV OAuth editor opened type=$type hasNext=$hasNext")
            onFocused(
                TvWebField(
                    id = id,
                    label = label,
                    type = type,
                    value = value,
                    hasNext = hasNext,
                ),
            )
        }
    }
}

internal fun tvWebFieldValueScript(
    fieldId: String,
    value: String,
    dispatchChange: Boolean = false,
    advance: Boolean = false,
    blur: Boolean = false,
): String {
    val idJson = JSONObject.quote(fieldId)
    val valueJson = JSONObject.quote(value)
    return """
        (function() {
          var el = document.querySelector('[data-miruro-tv-editor-id="' + $idJson + '"]');
          if (!el) return false;
          var value = $valueJson;
          if (el.isContentEditable) {
            el.textContent = value;
          } else {
            var proto = el.tagName.toLowerCase() === 'textarea'
              ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
            var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
            setter.call(el, value);
          }
          el.dispatchEvent(new Event('input', { bubbles: true }));
          ${if (dispatchChange) "el.dispatchEvent(new Event('change', { bubbles: true }));" else ""}
          if (${advance.toString()}) {
            var fields = Array.prototype.filter.call(
              document.querySelectorAll('input,textarea,[contenteditable="true"]'),
              function(candidate) {
                var tag = (candidate.tagName || '').toLowerCase();
                var type = (candidate.type || 'text').toLowerCase();
                return !candidate.disabled && !candidate.readOnly &&
                  (candidate.isContentEditable || tag === 'textarea' ||
                    (tag === 'input' && ['hidden','button','checkbox','radio','submit','reset','file','image','range','color'].indexOf(type) < 0));
              }
            );
            var next = fields[fields.indexOf(el) + 1];
            if (next) { next.focus(); return true; }
          }
          if (${blur.toString()}) el.blur();
          return true;
        })();
    """.trimIndent()
}
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginWebView(
    authorizeUrl: String,
    isRedirect: (String) -> Boolean,
    extractResult: (String) -> String?,
    onResult: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val device = LocalAppDeviceProfile.current
    var webView by remember(authorizeUrl) { mutableStateOf<WebView?>(null) }
    var tvEditor by remember(authorizeUrl) { mutableStateOf<TvWebField?>(null) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    try {
                        var tokenHandled = false
                        WebView(ctx).apply {
                            webView = this
                            isFocusable = true
                            isFocusableInTouchMode = true
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            webViewClient = object : WebViewClient() {
                                private fun handleRedirect(view: WebView?, url: String?): Boolean {
                                    if (tokenHandled || url == null || !isRedirect(url)) return false
                                    val result = extractResult(url) ?: return false
                                    tokenHandled = true
                                    view?.stopLoading()
                                    onResult(result)
                                    return true
                                }

                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    tvEditor = null
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

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    if (device.isTv) view?.evaluateJavascript(TV_EDITOR_JS, null)
                                }
                            }
                            if (device.isTv) {
                                addJavascriptInterface(
                                    TvEditorBridge(this) { field -> tvEditor = field },
                                    "MiruroTvEditor",
                                )
                            }
                            loadUrl(authorizeUrl)
                            if (device.isTv) post { requestFocus() }
                        }
                    } catch (e: Throwable) {
                        CrashReporter.logNonFatal("System WebView unavailable; login disabled", e)
                        android.view.View(ctx)
                    }
                },
                onRelease = { view ->
                    val web = view as? WebView ?: return@AndroidView
                    if (webView === web) webView = null
                    tvEditor = null
                    web.stopLoading()
                    web.loadUrl("about:blank")
                    web.clearHistory()
                    web.removeAllViews()
                    web.destroy()
                },
            )
            if (device.isTv) {
                tvEditor?.let { field ->
                    TvWebEditorDialog(
                        field = field,
                        onValueChange = { value ->
                            tvEditor = field.copy(value = value)
                            webView?.evaluateJavascript(
                                tvWebFieldValueScript(field.id, value),
                                null,
                            )
                        },
                        onNext = {
                            tvEditor = null
                            webView?.evaluateJavascript(
                                tvWebFieldValueScript(
                                    fieldId = field.id,
                                    value = field.value,
                                    dispatchChange = true,
                                    advance = true,
                                ),
                                null,
                            )
                        },
                        onDone = {
                            tvEditor = null
                            webView?.evaluateJavascript(
                                tvWebFieldValueScript(
                                    fieldId = field.id,
                                    value = field.value,
                                    dispatchChange = true,
                                    blur = true,
                                ),
                                null,
                            )
                        },
                    )
                }
            }
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
