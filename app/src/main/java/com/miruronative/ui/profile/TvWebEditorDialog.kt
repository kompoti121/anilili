package com.miruronative.ui.profile

import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.miruronative.ui.adaptive.TvNativeTextField
import com.miruronative.ui.adaptive.TvTextInputType
import com.miruronative.ui.adaptive.focusHighlight
import kotlinx.coroutines.delay

internal data class TvWebField(
    val id: String,
    val label: String,
    val type: String,
    val value: String,
    val hasNext: Boolean,
) {
    val isPassword: Boolean get() = type.equals("password", ignoreCase = true)
    val isEmail: Boolean
        get() = type.equals("email", ignoreCase = true) || label.contains("email", ignoreCase = true)
}

/**
 * A focus-isolated editor for TV WebViews. Keeping the real text session in a Compose dialog
 * prevents D-pad events from falling through to the web page while the system keyboard is open.
 * Values live only for the lifetime of this dialog and are mirrored to the selected DOM field.
 */
@Composable
internal fun TvWebEditorDialog(
    field: TvWebField,
    onValueChange: (String) -> Unit,
    onNext: () -> Unit,
    onDone: () -> Unit,
) {
    val fieldFocus = remember(field.id) { FocusRequester() }
    val actionFocus = remember(field.id) { FocusRequester() }
    val finishNext = onNext
    val finishDone = onDone

    Dialog(
        onDismissRequest = finishDone,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.widthIn(max = 680.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
            ) {
                Column(
                    Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = "Enter ${field.label.ifBlank { "text" }}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Press Select to open the keyboard. Your entry is sent only to the login page.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TvNativeTextField(
                        value = field.value,
                        onValueChange = onValueChange,
                        hint = field.label.ifBlank { "Text" },
                        modifier = Modifier.fillMaxWidth(),
                        inputType = when {
                            field.isPassword -> TvTextInputType.PASSWORD
                            field.isEmail -> TvTextInputType.EMAIL
                            else -> TvTextInputType.TEXT
                        },
                        imeAction = if (field.hasNext) {
                            EditorInfo.IME_ACTION_NEXT
                        } else {
                            EditorInfo.IME_ACTION_DONE
                        },
                        onImeAction = if (field.hasNext) finishNext else finishDone,
                        onMoveDown = { runCatching { actionFocus.requestFocus() } },
                        tvFocusRequester = fieldFocus,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (field.hasNext) {
                            OutlinedButton(
                                onClick = finishNext,
                                modifier = Modifier
                                    .focusRequester(actionFocus)
                                    .focusHighlight(RoundedCornerShape(24.dp)),
                            ) {
                                Text("Next")
                            }
                        }
                        Button(
                            onClick = finishDone,
                            modifier = Modifier
                                .then(
                                    if (field.hasNext) Modifier else Modifier.focusRequester(actionFocus),
                                )
                                .focusHighlight(RoundedCornerShape(24.dp)),
                        ) {
                            Text("Done")
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(field.id) {
        delay(80)
        runCatching { fieldFocus.requestFocus() }
    }
}
