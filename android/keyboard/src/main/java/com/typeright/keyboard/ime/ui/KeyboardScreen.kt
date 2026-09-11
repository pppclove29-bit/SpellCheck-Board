package com.typeright.keyboard.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.typeright.keyboard.ime.ImeActions
import com.typeright.keyboard.ime.ImeUiState

/** Fixed height of the suggestion bar slot: always reserved, even when empty, so the keyboard never jumps. */
val SuggestionBarHeight = 48.dp

@Composable
fun KeyboardScreen(state: ImeUiState, actions: ImeActions) {
    val colors = keyboardColors()
    CompositionLocalProvider(LocalKeyboardColors provides colors) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.background)
                .navigationBarsPadding(),
        ) {
            Box(Modifier.fillMaxWidth().height(SuggestionBarHeight)) {
                SuggestionBar(
                    bar = state.bar,
                    secure = state.secure,
                    actions = actions,
                )
                // Feedback (speech bubble / police banner / tip card) is overlaid inside the reserved slot: no layout jump.
                FeedbackOverlay(
                    feedback = state.feedback,
                    onDismiss = actions::onFeedbackDismiss,
                    onShare = actions::onShareFeedback,
                )
            }
            KeyboardView(
                layoutId = state.layout,
                shift = state.shift,
                enterLabel = state.enterLabel,
                lettersLabel = state.lettersLabel,
                actions = actions,
            )
        }
    }
}
