package com.miruronative.ui.adaptive

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.miruronative.diagnostics.DiagnosticsLog
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay

/**
 * TV: D-pad traversal must be able to pass over a text field without the on-screen keyboard
 * opening (Compose text fields summon the IME the moment they gain focus). Until the user
 * presses select, the field sits inside a focusable shell whose editor cannot take focus;
 * selecting the shell hands focus to the editor, which then opens the keyboard.
 *
 * The IME request escalates because TV boxes are hostile to it: the system drops the implicit
 * show that focus gain triggers in D-pad mode, some boxes ignore the first explicit request
 * while the IME process spins up, and boxes whose remote registers as a hardware keyboard
 * ignore non-forced requests entirely. So: explicit show, retried briefly, then a forced
 * InputMethodManager request — with diagnostics at each step so user reports show which path
 * a device took. On phones and tablets the field is emitted unchanged.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TvDeferredTextField(
    modifier: Modifier = Modifier,
    tvFocusRequester: FocusRequester? = null,
    field: @Composable (Modifier) -> Unit,
) {
    val device = LocalAppDeviceProfile.current
    if (!device.isTv) {
        Box(modifier) { field(Modifier) }
        return
    }
    var editing by remember { mutableStateOf(false) }
    var hadFocus by remember { mutableStateOf(false) }
    val editorFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val context = LocalContext.current
    val imeVisible by rememberUpdatedState(WindowInsets.isImeVisible)

    fun forceShowKeyboard() {
        val manager =
            context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        if (manager == null) {
            DiagnosticsLog.event("TvTextField no InputMethodManager")
            return
        }
        DiagnosticsLog.event(
            "TvTextField force show; enabled IMEs=" +
                manager.enabledInputMethodList.joinToString { it.id }.ifEmpty { "none" },
        )
        @Suppress("DEPRECATION")
        val accepted = manager.showSoftInput(
            view.findFocus() ?: view,
            InputMethodManager.SHOW_FORCED,
        )
        DiagnosticsLog.event("TvTextField force show accepted=$accepted")
    }

    Box(
        modifier
            .then(tvFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .focusProperties { canFocus = !editing }
            .focusHighlight(RoundedCornerShape(10.dp))
            .clickable(onClickLabel = "Edit text", role = Role.Button) { editing = true },
    ) {
        field(
            Modifier
                .focusRequester(editorFocus)
                .focusProperties { canFocus = editing }
                .onPreviewKeyEvent { event ->
                    // Back dismisses the keyboard but leaves the editor focused; pressing
                    // select again must bring the keyboard back.
                    if (editing && event.type == KeyEventType.KeyUp && event.key == Key.DirectionCenter) {
                        keyboard?.show()
                        if (!imeVisible) forceShowKeyboard()
                        true
                    } else {
                        false
                    }
                }
                .onFocusChanged { state ->
                    if (state.isFocused) {
                        hadFocus = true
                    } else if (hadFocus) {
                        // Editing ends when focus leaves the editor (D-pad down, Done, back).
                        hadFocus = false
                        editing = false
                    }
                },
        )
    }
    LaunchedEffect(editing) {
        if (!editing) return@LaunchedEffect
        editorFocus.requestFocus()
        // Let the input session attach before asking for the IME, or the show is dropped.
        awaitFrame()
        keyboard?.show()
        repeat(4) { attempt ->
            delay(150)
            if (imeVisible) {
                DiagnosticsLog.event("TvTextField keyboard visible after ~${(attempt + 1) * 150}ms")
                return@LaunchedEffect
            }
            keyboard?.show()
        }
        if (!imeVisible) forceShowKeyboard()
    }
}
