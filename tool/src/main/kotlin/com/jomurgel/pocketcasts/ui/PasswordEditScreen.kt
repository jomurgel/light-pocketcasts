package com.jomurgel.pocketcasts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thelightphone.lp3Keyboard.ui.LayoutOptions
import com.thelightphone.lp3Keyboard.ui.SpecialKey
import com.thelightphone.lp3Keyboard.ui.viewmodel.EnQwertyLp3KeyboardViewModel
import com.thelightphone.lp3Keyboard.ui.viewmodel.Lp3KeyboardViewModel
import com.thelightphone.lp3Keyboard.ui.viewmodel.Lp3RepeatableKeyboardCallback
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.keyboard.LightEmbeddedLp3Keyboard

private const val UNDERLINE_HEIGHT_DP = 1

/**
 * Full-screen password entry: same layout as the SDK's text editor, but the
 * value renders as dots unless the user toggles SHOW. Single-line; Return
 * submits.
 */
class PasswordEditScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val initialValue: String,
) : SimpleLightScreen<String>(sealedActivity) {

    @Composable
    override fun Content() {
        val textState = rememberTextFieldState(initialValue)
        val keyboardOptionsFlow = rememberKeyboardOptions()
        val themeColors by LightThemeController.colors.collectAsState()
        var reveal by remember { mutableStateOf(false) }

        val callback = remember(textState) {
            PasswordKeyboardCallback(
                state = textState,
                onReturn = { goBack(textState.text.toString()) },
            )
        }
        val keyboardViewModel: Lp3KeyboardViewModel<*> = viewModel<EnQwertyLp3KeyboardViewModel<*>>(
            key = "PasswordEditScreen-$title",
            factory = passwordKeyboardFactory(callback, keyboardOptionsFlow),
        )

        LightTheme(colors = themeColors) {
            Surface {
                Column(modifier = Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(
                            icon = LightIcons.BACK,
                            onClick = { goBack(null) },
                        ),
                        center = LightTopBarCenter.Text(title),
                        modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 2f.gridUnitsAsDp()),
                        contentAlignment = Alignment.TopStart,
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            val display = if (reveal) {
                                textState.text.toString()
                            } else {
                                "•".repeat(textState.text.length)
                            }
                            BasicText(
                                text = display,
                                style = LightThemeTokens.typography.heading
                                    .copy(color = LightThemeTokens.colors.content),
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.StartEllipsis,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(modifier = Modifier.height(0.5f.gridUnitsAsDp()))
                            Spacer(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(UNDERLINE_HEIGHT_DP.dp)
                                    .background(LightThemeTokens.colors.content),
                            )
                        }
                    }

                    LightEmbeddedLp3Keyboard(
                        viewModel = keyboardViewModel,
                        additionalBottomHeight = 5f.gridUnitsAsDp(),
                        bottomBar = {
                            LightBottomBar(
                                topPadding = 0.dp,
                                items = listOf(
                                    LightBarButton.Text(
                                        text = "SUBMIT",
                                        onClick = { goBack(textState.text.toString()) },
                                    ),
                                    LightBarButton.Text(
                                        text = if (reveal) "HIDE" else "SHOW",
                                        onClick = { reveal = !reveal },
                                    ),
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
}

private fun passwordKeyboardFactory(
    callback: Lp3RepeatableKeyboardCallback,
    keyboardOptionsFlow: kotlinx.coroutines.flow.StateFlow<com.thelightphone.lp3Keyboard.ui.KeyboardOptions>,
): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return EnQwertyLp3KeyboardViewModel<Unit>(
            callback,
            keyboardOptionsFlow = keyboardOptionsFlow,
            optionsForLayout = { LayoutOptions(!it.isRootLayout) },
        ) as T
    }
}

/**
 * Keyboard-to-TextFieldState glue for a single-line secret field. Mirrors the
 * SDK's internal TextInputKeyboardCallback: keys insert at the cursor,
 * backspace is surrogate-pair aware, long-press backspace deletes a word,
 * Return submits.
 */
private class PasswordKeyboardCallback(
    private val state: TextFieldState,
    private val onReturn: () -> Unit,
) : Lp3RepeatableKeyboardCallback {

    override fun onKeyPressed(code: Int) = Unit

    override fun onSpecialKeyPressed(key: SpecialKey) {
        if (key == SpecialKey.Space) insertAtCursor(" ")
    }

    override fun onKeyReleased(code: Int) {
        insertAtCursor(buildString { appendCodePoint(code) })
    }

    override fun onSpecialKeyReleased(key: SpecialKey) {
        when (key) {
            SpecialKey.Backspace -> {
                val before = state.text.subSequence(0, state.selection.min)
                deleteBeforeCursor(surrogateAwareDeleteCount(before))
            }
            SpecialKey.Return -> onReturn()
            else -> Unit
        }
    }

    override fun onKeyLongPressed(code: Int) = Unit

    override fun onSpecialKeyLongPressed(key: SpecialKey) {
        if (key == SpecialKey.Backspace) {
            val before = state.text.subSequence(0, state.selection.min)
            deleteBeforeCursor(deleteWordCount(before))
        }
    }

    override fun onKeyRepeated(code: Int) {
        insertAtCursor(buildString { appendCodePoint(code) })
    }

    override fun onSpecialKeyRepeated(specialKey: SpecialKey) {
        if (specialKey == SpecialKey.Space) insertAtCursor(" ")
    }

    override fun onSubmitWord(word: CharSequence) {
        insertAtCursor(word.toString())
    }

    private fun insertAtCursor(text: String) {
        state.edit {
            val start = selection.min
            val end = selection.max
            replace(start, end, text)
            selection = TextRange(start + text.length)
        }
    }

    private fun deleteBeforeCursor(count: Int) {
        if (count <= 0) return
        state.edit {
            val end = selection.min
            if (end == 0) return@edit
            val start = (end - count).coerceAtLeast(0)
            delete(start, end)
            selection = TextRange(start)
        }
    }
}

private fun surrogateAwareDeleteCount(value: CharSequence): Int {
    if (value.isEmpty()) return 0
    val last = value[value.length - 1]
    return if (Character.isLowSurrogate(last)) 2 else 1
}

private fun deleteWordCount(value: CharSequence): Int {
    val trimmed = value.trimEnd()
    val lastSpace = trimmed.indexOfLast { it.isWhitespace() }
    return value.length - if (lastSpace >= 0) lastSpace + 1 else 0
}
