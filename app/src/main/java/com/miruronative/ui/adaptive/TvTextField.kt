package com.miruronative.ui.adaptive

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.miruronative.diagnostics.DiagnosticsLog

enum class TvTextInputType {
    TEXT,
    EMAIL,
    PASSWORD,
    NUMBER,
}

/**
 * A real Android [EditText] hosted inside Compose for TV.
 *
 * Fire TV's IME can acknowledge a show request without ever attaching to a Compose text input.
 * A native editor provides the platform input connection expected by Fire TV IME, TalkBack, and
 * phone-remote keyboards. D-pad focus alone does not summon the keyboard; Select/Enter/Button A
 * explicitly begins editing.
 */
@Composable
fun TvNativeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    inputType: TvTextInputType = TvTextInputType.TEXT,
    imeAction: Int = EditorInfo.IME_ACTION_DONE,
    onImeAction: () -> Unit = {},
    onMoveDown: (() -> Unit)? = null,
    tvFocusRequester: FocusRequester? = null,
) {
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnImeAction by rememberUpdatedState(onImeAction)
    val currentOnMoveDown by rememberUpdatedState(onMoveDown)
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val surfaceColor = MaterialTheme.colorScheme.surface.toArgb()
    val outlineColor = MaterialTheme.colorScheme.outline.toArgb()
    val focusedOutlineColor = MaterialTheme.colorScheme.primary.toArgb()
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(androidx.compose.ui.graphics.Color(surfaceColor))
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = androidx.compose.ui.graphics.Color(
                    if (focused) focusedOutlineColor else outlineColor,
                ),
                shape = shape,
            ),
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .then(tvFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
            factory = { context ->
                EditText(context).apply {
                    tag = NativeTvTextWatcher(this, currentOnValueChange).also(::addTextChangedListener)
                    setText(value)
                    setSelection(value.length)
                    setSingleLine(true)
                    this.hint = hint
                    setTextColor(textColor)
                    setHintTextColor(hintColor)
                    textSize = 18f
                    background = ColorDrawable(Color.TRANSPARENT)
                    val horizontal = (16 * resources.displayMetrics.density).toInt()
                    setPadding(horizontal, 0, horizontal, 0)
                    this.inputType = inputType.androidValue
                    this.imeOptions = imeAction
                    isFocusable = true
                    isFocusableInTouchMode = true
                    showSoftInputOnFocus = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES

                    setOnFocusChangeListener { _, hasFocus ->
                        focused = hasFocus
                        if (!hasFocus) showSoftInputOnFocus = false
                    }
                    setOnClickListener { beginNativeTvEditing(this) }
                    setOnKeyListener { _, keyCode, event ->
                        when {
                            event.action == KeyEvent.ACTION_DOWN && keyCode in TV_SELECT_KEYS -> {
                                beginNativeTvEditing(this)
                                true
                            }
                            event.action == KeyEvent.ACTION_DOWN &&
                                keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
                                currentOnMoveDown != null -> {
                                hideNativeTvKeyboard(this)
                                currentOnMoveDown?.invoke()
                                true
                            }
                            else -> false
                        }
                    }
                    setOnEditorActionListener { _, actionId, event ->
                        val requestedAction = actionId == imeAction ||
                            actionId == EditorInfo.IME_ACTION_DONE ||
                            actionId == EditorInfo.IME_ACTION_SEARCH ||
                            actionId == EditorInfo.IME_ACTION_NEXT
                        val enterKey = event?.action == KeyEvent.ACTION_DOWN &&
                            event.keyCode in TV_SELECT_KEYS
                        if (requestedAction || enterKey) {
                            hideNativeTvKeyboard(this)
                            currentOnImeAction()
                            true
                        } else {
                            false
                        }
                    }
                }
            },
            update = { editor ->
                editor.hint = hint
                editor.setTextColor(textColor)
                editor.setHintTextColor(hintColor)
                editor.inputType = inputType.androidValue
                editor.imeOptions = imeAction
                (editor.tag as? NativeTvTextWatcher)?.onValueChange = currentOnValueChange
                if (editor.text.toString() != value) {
                    val selection = editor.selectionStart.coerceAtLeast(0)
                    editor.setText(value)
                    editor.setSelection(selection.coerceAtMost(value.length))
                }
            },
        )
    }
}

private class NativeTvTextWatcher(
    private val editor: EditText,
    var onValueChange: (String) -> Unit,
) : TextWatcher {
    override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
    override fun afterTextChanged(text: Editable?) {
        onValueChange(text?.toString().orEmpty())
        editor.postInvalidate()
    }
}

private fun beginNativeTvEditing(editor: EditText) {
    editor.showSoftInputOnFocus = true
    editor.requestFocus()
    editor.setSelection(editor.text.length)
    val manager = editor.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    if (manager == null) {
        DiagnosticsLog.event("TvNativeTextField no InputMethodManager")
        return
    }
    editor.post {
        val accepted = manager.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        DiagnosticsLog.event(
            "TvNativeTextField show accepted=$accepted ime=${manager.enabledInputMethodList.joinToString { it.id }}",
        )
        editor.postDelayed({
            if (!editor.isAttachedToWindow || editor.isImeVisible()) return@postDelayed
            @Suppress("DEPRECATION")
            val forced = manager.showSoftInput(editor, InputMethodManager.SHOW_FORCED)
            DiagnosticsLog.event("TvNativeTextField forced show accepted=$forced")
        }, 250)
    }
}

private fun hideNativeTvKeyboard(editor: EditText) {
    val manager = editor.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    manager?.hideSoftInputFromWindow(editor.windowToken, 0)
    editor.showSoftInputOnFocus = false
}

private fun View.isImeVisible(): Boolean =
    ViewCompat.getRootWindowInsets(this)?.isVisible(WindowInsetsCompat.Type.ime()) == true

private val TvTextInputType.androidValue: Int
    get() = when (this) {
        TvTextInputType.TEXT -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        TvTextInputType.EMAIL -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        TvTextInputType.PASSWORD ->
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        TvTextInputType.NUMBER -> InputType.TYPE_CLASS_NUMBER
    }

private val TV_SELECT_KEYS = setOf(
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER,
    KeyEvent.KEYCODE_BUTTON_A,
    KeyEvent.KEYCODE_BUTTON_SELECT,
)
