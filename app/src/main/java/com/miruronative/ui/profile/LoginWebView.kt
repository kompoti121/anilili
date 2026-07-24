package com.miruronative.ui.profile

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
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

  var focusStyle = document.createElement('style');
  focusStyle.id = 'miruro-tv-focus-style';
  focusStyle.textContent =
    '[data-miruro-tv-focused="true"]{' +
    'outline:4px solid #8f7cff !important;' +
    'outline-offset:4px !important;' +
    'box-shadow:0 0 0 6px rgba(143,124,255,.28) !important;' +
    '}';
  (document.head || document.documentElement).appendChild(focusStyle);

  function editable(el) {
    if (!el) return false;
    if (el.isContentEditable) return true;
    var tag = (el.tagName || '').toLowerCase();
    if (tag === 'textarea') return true;
    if (tag !== 'input') return false;
    var t = (el.type || 'text').toLowerCase();
    return ['hidden','button','checkbox','radio','submit','reset','file','image','range','color'].indexOf(t) < 0;
  }
  function visible(el) {
    if (!el) return false;
    var rect = el.getBoundingClientRect();
    var style = window.getComputedStyle(el);
    return rect.width > 0 && rect.height > 0 &&
      style.display !== 'none' && style.visibility !== 'hidden' &&
      Number(style.opacity || 1) > 0;
  }
  function editables() {
    return Array.prototype.filter.call(
      document.querySelectorAll('input,textarea,[contenteditable="true"]'),
      function(el) { return editable(el) && visible(el) && !el.disabled && !el.readOnly; }
    );
  }
  function controls() {
    return Array.prototype.filter.call(
      document.querySelectorAll(
        'input,textarea,[contenteditable="true"],button,select,a[href],[role="button"],' +
        '[tabindex],[onclick],form .submit'
      ),
      function(el) {
        return visible(el) && !el.disabled && el.getAttribute('tabindex') !== '-1';
      }
    );
  }
  function markFocused(el) {
    var previous = window.__miruroTvControl;
    if (previous && previous !== el) previous.removeAttribute('data-miruro-tv-focused');
    window.__miruroTvControl = el;
    el.setAttribute('data-miruro-tv-focused', 'true');
  }
  function reportEditable(el) {
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
  }
  function focusControl(el) {
    if (!el) return false;
    var alreadyFocused = document.activeElement === el;
    markFocused(el);
    if (el.tabIndex < 0 && !editable(el)) el.setAttribute('tabindex', '0');
    try { el.focus({ preventScroll: false }); } catch (_) { el.focus(); }
    try { el.scrollIntoView({ block: 'center', inline: 'nearest' }); } catch (_) {}
    if (editable(el) && alreadyFocused) reportEditable(el);
    return true;
  }
  document.addEventListener('focusin', function(e) {
    var el = e.target;
    if (!el || !visible(el)) return;
    markFocused(el);
    if (editable(el)) reportEditable(el);
  }, true);

  window.__miruroTvMoveFocus = function(delta) {
    var items = controls();
    if (!items.length) return false;
    var current = window.__miruroTvControl;
    var index = items.indexOf(current);
    if (index < 0) {
      if (delta >= 0) {
        var firstEditable = items.filter(editable)[0];
        return focusControl(firstEditable || items[0]);
      }
      return focusControl(items[items.length - 1]);
    }
    var next = Math.max(0, Math.min(items.length - 1, index + (delta >= 0 ? 1 : -1)));
    return focusControl(items[next]);
  };
  window.__miruroTvFocusInitial = function() {
    var current = window.__miruroTvControl;
    if (current && document.documentElement.contains(current)) return true;
    return window.__miruroTvMoveFocus(1);
  };
  window.__miruroTvActivate = function() {
    var el = window.__miruroTvControl || document.activeElement;
    if (!el || el === document.body || el === document.documentElement) {
      return window.__miruroTvMoveFocus(1);
    }
    if (editable(el)) {
      reportEditable(el);
      return true;
    }
    el.click();
    return true;
  };
  window.__miruroTvFocusAfter = function(el) {
    var form = el.form || (el.closest ? el.closest('form') : null);
    if (form) {
      var submit = Array.prototype.filter.call(
        form.querySelectorAll(
          'button[type="submit"],input[type="submit"],button:not([type]),.submit,[role="button"],[onclick]'
        ),
        visible
      )[0];
      if (submit) return focusControl(submit);
    }
    var items = controls();
    var index = items.indexOf(el);
    if (index >= 0 && index < items.length - 1) {
      return focusControl(items[index + 1]);
    }
    el.blur();
    return false;
  };
})();
"""

private const val TV_FOCUS_FIRST_JS =
    "window.__miruroTvFocusInitial && window.__miruroTvFocusInitial();"
private const val TV_FOCUS_NEXT_JS =
    "window.__miruroTvMoveFocus && window.__miruroTvMoveFocus(1);"
private const val TV_FOCUS_PREVIOUS_JS =
    "window.__miruroTvMoveFocus && window.__miruroTvMoveFocus(-1);"
private const val TV_ACTIVATE_CONTROL_JS =
    "window.__miruroTvActivate && window.__miruroTvActivate();"

private val TV_WEB_SELECT_KEYS = setOf(
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER,
    KeyEvent.KEYCODE_BUTTON_A,
    KeyEvent.KEYCODE_BUTTON_SELECT,
)

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
          if (${blur.toString()}) {
            if (window.__miruroTvFocusAfter) {
              window.__miruroTvFocusAfter(el);
              // Some TV IMEs replay their confirming key after the native editor closes. Restore
              // the intended form action once that replay has finished so focus cannot escape to
              // an unrelated page control (AniList's floating forum menu is a common example).
              window.setTimeout(function() {
                if (el.isConnected && window.__miruroTvFocusAfter) {
                  window.__miruroTvFocusAfter(el);
                }
              }, 180);
            } else {
              el.blur();
            }
          }
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
    var pageLoading by remember(authorizeUrl) { mutableStateOf(true) }
    var loadProblem by remember(authorizeUrl) { mutableStateOf<String?>(null) }

    LaunchedEffect(authorizeUrl, webView, pageLoading) {
        val activeWebView = webView ?: return@LaunchedEffect
        if (!pageLoading) return@LaunchedEffect
        kotlinx.coroutines.delay(LOGIN_LOAD_TIMEOUT_MS)
        if (pageLoading && activeWebView.isAttachedToWindow) {
            DiagnosticsLog.event(
                "LoginWebView load timeout host=${activeWebView.url.oauthHost()} " +
                    "progress=${activeWebView.progress} title=${activeWebView.title ?: "none"}",
            )
            loadProblem = "The login page is taking too long to open."
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    try {
                        var tokenHandled = false
                        WebView(ctx).apply {
                            DiagnosticsLog.event("LoginWebView factory create start")
                            DiagnosticsLog.webViewPackage("LoginWebView factory")
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
                                    pageLoading = true
                                    loadProblem = null
                                    DiagnosticsLog.event(
                                        "LoginWebView page started host=${url.oauthHost()}",
                                    )
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
                                    if (url == "about:blank") return
                                    pageLoading = false
                                    loadProblem = null
                                    DiagnosticsLog.event(
                                        "LoginWebView page finished host=${url.oauthHost()} " +
                                            "title=${view?.title ?: "none"}",
                                    )
                                    if (device.isTv) {
                                        view?.evaluateJavascript(TV_EDITOR_JS) {
                                            view.postDelayed({
                                                if (view.isAttachedToWindow) {
                                                    view.evaluateJavascript(TV_FOCUS_FIRST_JS, null)
                                                }
                                            }, 180)
                                        }
                                    }
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?,
                                ) {
                                    if (request?.isForMainFrame != true) return
                                    pageLoading = false
                                    loadProblem = "Couldn't open the login page."
                                    val details =
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                            "code=${error?.errorCode ?: 0} " +
                                                "description=${error?.description ?: "none"}"
                                        } else {
                                            "details=unavailable"
                                        }
                                    DiagnosticsLog.event(
                                        "LoginWebView main frame error host=${request.url?.host ?: "none"} " +
                                            details,
                                    )
                                }

                                override fun onReceivedHttpError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    errorResponse: WebResourceResponse?,
                                ) {
                                    if (request?.isForMainFrame != true) return
                                    pageLoading = false
                                    loadProblem = "The login service returned an error."
                                    DiagnosticsLog.event(
                                        "LoginWebView main frame HTTP error " +
                                            "host=${request.url?.host ?: "none"} " +
                                            "status=${errorResponse?.statusCode ?: 0}",
                                    )
                                }
                            }
                            if (device.isTv) {
                                addJavascriptInterface(
                                    TvEditorBridge(this) { field -> tvEditor = field },
                                    "MiruroTvEditor",
                                )
                                setOnKeyListener { _, keyCode, event ->
                                    if (event.action != KeyEvent.ACTION_DOWN) {
                                        return@setOnKeyListener false
                                    }
                                    val script = when {
                                        keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> TV_FOCUS_NEXT_JS
                                        keyCode == KeyEvent.KEYCODE_DPAD_UP -> TV_FOCUS_PREVIOUS_JS
                                        keyCode in TV_WEB_SELECT_KEYS -> TV_ACTIVATE_CONTROL_JS
                                        else -> return@setOnKeyListener false
                                    }
                                    evaluateJavascript(script, null)
                                    true
                                }
                            }
                            loadUrl(authorizeUrl)
                            DiagnosticsLog.event(
                                "LoginWebView load requested host=${authorizeUrl.oauthHost()}",
                            )
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
                    DiagnosticsLog.event(
                        "LoginWebView release host=${web.url.oauthHost()} size=${web.width}x${web.height}",
                    )
                    web.stopLoading()
                    web.loadUrl("about:blank")
                    web.clearHistory()
                    web.removeAllViews()
                    web.destroy()
                },
            )
            if (pageLoading || loadProblem != null) {
                LoginLoadStatus(
                    problem = loadProblem,
                    onRetry = {
                        val activeWebView = webView ?: return@LoginLoadStatus
                        DiagnosticsLog.event(
                            "LoginWebView retry host=${activeWebView.url.oauthHost()}",
                        )
                        pageLoading = true
                        loadProblem = null
                        activeWebView.stopLoading()
                        activeWebView.loadUrl("about:blank")
                        activeWebView.postDelayed({
                            if (activeWebView.isAttachedToWindow) {
                                activeWebView.loadUrl(authorizeUrl)
                            }
                        }, LOGIN_RETRY_DELAY_MS)
                    },
                )
            }
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
                            val activeWebView = webView
                            tvEditor = null
                            activeWebView?.evaluateJavascript(
                                tvWebFieldValueScript(
                                    fieldId = field.id,
                                    value = field.value,
                                    dispatchChange = true,
                                    blur = true,
                                ),
                            ) {
                                activeWebView.postDelayed({
                                    if (activeWebView.isAttachedToWindow) {
                                        activeWebView.requestFocus()
                                    }
                                }, 100)
                            }
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

@Composable
private fun LoginLoadStatus(
    problem: String?,
    onRetry: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (problem == null) {
                CircularProgressIndicator()
                Text(
                    text = "Opening secure login…",
                    modifier = Modifier.padding(top = 20.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
            } else {
                Text(
                    text = problem,
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "Check your connection, then try again.",
                    modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = onRetry) {
                    Text("Retry")
                }
            }
        }
    }
}

private fun String?.oauthHost(): String =
    this?.let { value -> runCatching { android.net.Uri.parse(value).host }.getOrNull() }
        ?: "none"

private const val LOGIN_LOAD_TIMEOUT_MS = 12_000L
private const val LOGIN_RETRY_DELAY_MS = 180L
