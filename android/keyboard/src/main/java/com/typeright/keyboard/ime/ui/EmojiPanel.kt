package com.typeright.keyboard.ime.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typeright.keyboard.emoji.EmojiCatalog
import com.typeright.keyboard.emoji.RecentEmoji
import com.typeright.keyboard.ime.ImeActions
import com.typeright.keyboard.ime.KeyFeedback
import com.typeright.keyboard.ime.layout.KeyAction
import com.typeright.keyboard.ime.layout.KeyKind
import com.typeright.keyboard.ime.layout.KeySpec
import kotlinx.coroutines.withTimeoutOrNull

/** Same height as the 4-row key grid (4 × 52dp + 8dp padding) so opening the panel never resizes the keyboard. */
val EmojiPanelHeight = 216.dp

private val TabRowHeight = 34.dp
private val BottomRowHeight = 42.dp
private const val COLUMNS = 8
private const val REPEAT_START_MS = 400L
private const val REPEAT_INTERVAL_MS = 55L

private val backspaceKey = KeySpec("⌫", KeyAction.Backspace, kind = KeyKind.FUNCTION, repeatable = true)

/**
 * Emoji panel shown in place of the key grid. Picking an emoji keeps the panel open (people send several in a row);
 * 가/A and ⌫ sit in the bottom bar so the user never has to leave the panel to fix a mistake.
 */
@Composable
fun EmojiPanel(recent: List<String>, feedback: KeyFeedback, actions: ImeActions) {
    val colors = LocalKeyboardColors.current
    // Tab 0 is 최근; the rest map onto EmojiCatalog.categories in order.
    var tab by remember { mutableStateOf(0) }
    val display = remember(recent) { RecentEmoji.display(recent) }
    val emoji = if (tab == 0) display else EmojiCatalog.categories[tab - 1].emoji
    val gridState = rememberLazyGridState()
    // Switching tabs must start at the top; the grid state is shared across tabs.
    LaunchedEffect(tab) { gridState.scrollToItem(0) }

    Column(Modifier.fillMaxWidth().height(EmojiPanelHeight).background(colors.background)) {
        EmojiTabs(selected = tab, onSelect = { tab = it })
        Box(Modifier.fillMaxWidth().weight(1f)) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(COLUMNS),
                state = gridState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
            ) {
                items(emoji, key = { it }) { e ->
                    EmojiCell(e, feedback) { actions.onEmojiPick(e) }
                }
            }
        }
        EmojiBottomBar(feedback = feedback, actions = actions)
    }
}

@Composable
private fun EmojiTabs(selected: Int, onSelect: (Int) -> Unit) {
    val colors = LocalKeyboardColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(TabRowHeight)
            .background(colors.bar)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EmojiTab(EmojiCatalog.RECENT_TAB, EmojiCatalog.RECENT_LABEL, selected == 0) { onSelect(0) }
        EmojiCatalog.categories.forEachIndexed { index, category ->
            EmojiTab(category.tab, category.label, selected == index + 1) { onSelect(index + 1) }
        }
    }
}

@Composable
private fun EmojiTab(tab: String, label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalKeyboardColors.current
    Box(
        Modifier
            .padding(horizontal = 3.dp, vertical = 3.dp)
            .size(width = 38.dp, height = TabRowHeight - 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) colors.keyPressed else colors.bar)
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(tab, fontSize = 17.sp)
    }
}

@Composable
private fun EmojiCell(emoji: String, feedback: KeyFeedback, onPick: () -> Unit) {
    val view = LocalView.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clickable {
                if (feedback.vibrate) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                playKeySound(view, feedback, KeyAction.ShowEmoji)
                onPick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, fontSize = 24.sp)
    }
}

@Composable
private fun EmojiBottomBar(feedback: KeyFeedback, actions: ImeActions) {
    val colors = LocalKeyboardColors.current
    val view = LocalView.current
    Row(
        Modifier.fillMaxWidth().height(BottomRowHeight).background(colors.bar),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(4.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(colors.functionKey)
                .clickable {
                    if (feedback.vibrate) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    actions.onEmojiPanelClose()
                },
            contentAlignment = Alignment.Center,
        ) {
            Text("가/A", color = colors.keyText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Box(Modifier.weight(2f))
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(4.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(colors.functionKey)
                // Hold to repeat, same as ⌫ on the key grid.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        if (feedback.vibrate) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        playKeySound(view, feedback, KeyAction.Backspace)
                        actions.onKey(backspaceKey)
                        var released = withTimeoutOrNull(REPEAT_START_MS) { waitForUpOrCancellation() } != null
                        while (!released) {
                            actions.onKey(backspaceKey)
                            released = withTimeoutOrNull(REPEAT_INTERVAL_MS) { waitForUpOrCancellation() } != null
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text("⌫", color = colors.keyText, fontSize = 18.sp)
        }
    }
}
