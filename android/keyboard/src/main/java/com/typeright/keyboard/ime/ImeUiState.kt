package com.typeright.keyboard.ime

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.typeright.keyboard.check.CheckSnapshot
import com.typeright.keyboard.ime.layout.KeySpec
import com.typeright.keyboard.ime.layout.LayoutId
import com.typeright.keyboard.rules.Correction
import com.typeright.keyboard.rules.FeedbackMode
import com.typeright.keyboard.settings.Shortcut

enum class ShiftState { OFF, ONCE, LOCKED }

enum class BarStatus(val label: String) {
    NONE(""),
    CHECKING("AI 검사 중"),
    OFFLINE("오프라인"),
    AI_OFF("AI 꺼짐"),
    QUOTA_EMPTY("AI 훈수 소진"),
    SECURE("보안 키패드"),
}

sealed interface ChipUi {
    data class CorrectionChip(val correction: Correction, val snapshot: CheckSnapshot) : ChipUi

    /** 맞춤법 경찰 '무시': ignore this span for the current sentence. */
    data class IgnoreChip(val correction: Correction, val snapshot: CheckSnapshot) : ChipUi
    data class ShortcutChip(val shortcut: Shortcut) : ChipUi

    /** "광고 보고 AI 훈수 3회 충전" → host app `typeright://reward`. */
    data object RechargeChip : ChipUi
}

@Immutable
data class BarState(
    val chips: List<ChipUi> = emptyList(),
    val status: BarStatus = BarStatus.NONE,
    val isPro: Boolean = false,
)

enum class FeedbackStyle { BUBBLE, POLICE, TIP, REASON }

@Immutable
data class FeedbackUi(val id: Long, val style: FeedbackStyle, val text: String) {
    companion object {
        fun styleFor(mode: FeedbackMode): FeedbackStyle = when (mode) {
            FeedbackMode.SPICY_WIT -> FeedbackStyle.BUBBLE
            FeedbackMode.POLICE -> FeedbackStyle.POLICE
            FeedbackMode.GENTLE -> FeedbackStyle.TIP
        }
    }
}

/**
 * Compose-observable keyboard state. Keystrokes do not write here (only shift/layout changes and debounced check
 * results do), so typing does not recompose the key grid.
 */
class ImeUiState {
    var layout by mutableStateOf(LayoutId.DUBEOLSIK)
    var shift by mutableStateOf(ShiftState.OFF)
    var secure by mutableStateOf(false)
    var enterLabel by mutableStateOf("↵")
    var lettersLabel by mutableStateOf("가")
    var bar by mutableStateOf(BarState())
    var feedback by mutableStateOf<FeedbackUi?>(null)
}

/** Callbacks from the Compose UI into the IME service. */
interface ImeActions {
    fun onKey(spec: KeySpec)
    fun onKeyLongPress(spec: KeySpec)
    fun onChipClick(chip: ChipUi)
    fun onChipLongPress(chip: ChipUi)
    fun onFeedbackDismiss(id: Long)
    fun onSwitchToPreviousKeyboard()
}
