package com.typeright.keyboard.ime.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typeright.keyboard.ime.ImeActions
import com.typeright.keyboard.ime.KeyFeedback
import com.typeright.keyboard.ime.ShiftState
import com.typeright.keyboard.ime.layout.KeyAction
import com.typeright.keyboard.ime.layout.KeyKind
import com.typeright.keyboard.ime.layout.KeySpec
import com.typeright.keyboard.ime.layout.KeyboardLayouts
import com.typeright.keyboard.ime.layout.LayoutId
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

private val RowHeight = 52.dp
private const val LONG_PRESS_MS = 450L
private const val REPEAT_START_MS = 400L
private const val REPEAT_INTERVAL_MS = 55L

/**
 * Key grid. Only layout/shift/labels are state here; key presses go straight to [ImeActions] and pressed feedback
 * is drawn in the draw phase, so typing never recomposes the grid.
 */
@Composable
fun KeyboardView(
    layoutId: LayoutId,
    shift: ShiftState,
    enterLabel: String,
    lettersLabel: String,
    feedback: KeyFeedback,
    actions: ImeActions,
) {
    val layout = KeyboardLayouts.get(layoutId)
    Column(Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 4.dp)) {
        for (row in layout.rows) {
            Row(Modifier.fillMaxWidth().height(RowHeight)) {
                for (spec in row) {
                    if (spec.kind == KeyKind.SPACER) {
                        Spacer(Modifier.weight(spec.weight))
                    } else {
                        val label = when {
                            spec.action == KeyAction.Enter -> enterLabel
                            spec.action == KeyAction.Letters -> lettersLabel
                            spec.action == KeyAction.Shift && shift == ShiftState.LOCKED -> "⇪"
                            shift != ShiftState.OFF && spec.shiftLabel != null -> spec.shiftLabel
                            else -> spec.label
                        }
                        Key(
                            spec = spec,
                            label = label,
                            highlighted = spec.action == KeyAction.Shift && shift != ShiftState.OFF,
                            feedback = feedback,
                            actions = actions,
                            modifier = Modifier.weight(spec.weight),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Key(
    spec: KeySpec,
    label: String,
    highlighted: Boolean,
    feedback: KeyFeedback,
    actions: ImeActions,
    modifier: Modifier,
) {
    val colors = LocalKeyboardColors.current
    val view = LocalView.current
    val pressed: MutableState<Boolean> = remember { mutableStateOf(false) }
    val base = when {
        highlighted -> colors.keyPressed
        spec.kind == KeyKind.ACCENT -> colors.accentKey
        spec.kind == KeyKind.FUNCTION -> colors.functionKey
        else -> colors.key
    }
    val textColor = if (spec.kind == KeyKind.ACCENT) colors.accentText else colors.keyText

    Box(
        modifier = modifier
            .fillMaxHeight()
            .padding(horizontal = 2.5.dp, vertical = 3.dp)
            .drawBehind {
                // Read in the draw phase only: pressing a key redraws it without recomposition.
                drawRoundRect(
                    color = if (pressed.value) colors.keyPressed else base,
                    cornerRadius = CornerRadius(7.dp.toPx()),
                )
            }
            .pointerInput(spec, feedback) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed.value = true
                    if (feedback.vibrate) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    playKeySound(view, feedback, spec.action)
                    when {
                        spec.longPress != null -> {
                            // Tap on release, long-press action after LONG_PRESS_MS.
                            val up = withTimeoutOrNull(LONG_PRESS_MS) { waitForUpOrCancellation() }
                            if (up != null) {
                                actions.onKey(spec)
                            } else {
                                actions.onKeyLongPress(spec)
                                waitForUpOrCancellation()
                            }
                        }
                        spec.repeatable -> {
                            actions.onKey(spec)
                            var up = withTimeoutOrNull(REPEAT_START_MS) { waitForUpOrCancellation() }
                            var released = up != null
                            while (!released) {
                                actions.onKey(spec)
                                up = withTimeoutOrNull(REPEAT_INTERVAL_MS) { waitForUpOrCancellation() }
                                released = up != null
                            }
                        }
                        else -> {
                            // Fire on touch-down for the lowest latency.
                            actions.onKey(spec)
                            waitForUpOrCancellation()
                        }
                    }
                    pressed.value = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = if (label.length > 3) 13.sp else 19.sp,
            fontWeight = if (spec.kind == KeyKind.CHAR) FontWeight.Normal else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
        spec.hint?.let {
            Text(
                text = it,
                color = colors.hintText,
                fontSize = 8.sp,
                maxLines = 1,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 1.dp),
            )
        }
    }
}

@Suppress("unused")
private suspend fun keepAlive() = delay(0)
