package com.miruronative.ui.adaptive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.android.awaitFrame

/**
 * TV: D-pad traversal must be able to pass over a text field without the on-screen keyboard
 * opening (Compose text fields summon the IME the moment they gain focus). Until the user
 * presses select, the field sits inside a focusable shell whose editor cannot take focus;
 * selecting the shell hands focus to the editor, which then opens the keyboard. The IME is
 * requested explicitly because on TV (non-touch mode) the system routinely ignores the
 * implicit show that focus gain triggers, leaving the user with a focused field and no
 * keyboard. On phones and tablets the field is emitted unchanged.
 */
@Composable
fun TvDeferredTextField(
    modifier: Modifier = Modifier,
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
    Box(
        modifier
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
        if (editing) {
            editorFocus.requestFocus()
            // Let the input session attach before asking for the IME, or the show is dropped.
            awaitFrame()
            keyboard?.show()
        }
    }
}
