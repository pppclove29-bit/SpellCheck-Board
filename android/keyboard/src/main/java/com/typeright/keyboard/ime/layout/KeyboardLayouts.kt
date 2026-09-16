package com.typeright.keyboard.ime.layout

import androidx.compose.runtime.Immutable
import com.typeright.keyboard.hangul.CheonjiinConsonantKey
import com.typeright.keyboard.hangul.CheonjiinStroke

sealed interface KeyAction {
    /** 두벌식 jamo fed to the Hangul automaton. */
    data class Jamo(val jamo: Char) : KeyAction

    /** Plain text committed as-is (English, digits, symbols). */
    data class Text(val text: String) : KeyAction

    data class CjConsonant(val key: CheonjiinConsonantKey) : KeyAction
    data class CjStroke(val stroke: CheonjiinStroke) : KeyAction

    /** 천지인 punctuation key: cycles . , ? ! on repeated taps. */
    data object CjPunctuation : KeyAction
    data object Backspace : KeyAction
    data object Shift : KeyAction
    data object Space : KeyAction
    data object Enter : KeyAction

    /** 한/영. */
    data object ToggleLanguage : KeyAction

    /** 두벌식 ↔ 천지인 (long-press on 한/영). */
    data object SwitchKoreanLayout : KeyAction
    data object Symbols : KeyAction
    data object Letters : KeyAction

    /** Opens the emoji panel in place of the key grid. */
    data object ShowEmoji : KeyAction
}

enum class KeyKind { CHAR, FUNCTION, ACCENT, SPACE, SPACER }

@Immutable
data class KeySpec(
    val label: String,
    val action: KeyAction?,
    val weight: Float = 1f,
    val shiftLabel: String? = null,
    val shiftAction: KeyAction? = null,
    val kind: KeyKind = KeyKind.CHAR,
    val repeatable: Boolean = false,
    val longPress: KeyAction? = null,
    /** Small secondary label (e.g. the long-press hint). */
    val hint: String? = null,
)

enum class LayoutId { DUBEOLSIK, CHEONJIIN, QWERTY, SYMBOLS }

@Immutable
data class KeyboardLayout(val id: LayoutId, val rows: List<List<KeySpec>>) {
    val isKorean: Boolean get() = id == LayoutId.DUBEOLSIK || id == LayoutId.CHEONJIIN
    val hasShift: Boolean get() = id == LayoutId.DUBEOLSIK || id == LayoutId.QWERTY
}

object KeyboardLayouts {

    private fun jamo(c: Char, shifted: Char? = null) = KeySpec(
        label = c.toString(),
        action = KeyAction.Jamo(c),
        shiftLabel = shifted?.toString(),
        shiftAction = shifted?.let { KeyAction.Jamo(it) },
    )

    private fun letter(c: Char) = KeySpec(
        label = c.toString(),
        action = KeyAction.Text(c.toString()),
        shiftLabel = c.uppercaseChar().toString(),
        shiftAction = KeyAction.Text(c.uppercaseChar().toString()),
    )

    /**
     * Top-row digits on long-press, as on every mainstream Korean keyboard: typing one number should not cost a
     * trip through ?123 and back. The digit is shown as the key's hint so it is discoverable.
     */
    private fun withDigit(keys: List<KeySpec>): List<KeySpec> =
        keys.mapIndexed { index, spec ->
            val digit = DIGIT_ROW[index % DIGIT_ROW.length].toString()
            spec.copy(longPress = KeyAction.Text(digit), hint = digit)
        }

    private const val DIGIT_ROW = "1234567890"

    private fun text(s: String, weight: Float = 1f) = KeySpec(s, KeyAction.Text(s), weight)

    private fun spacer(weight: Float) = KeySpec("", null, weight, kind = KeyKind.SPACER)

    private val shift = KeySpec("⇧", KeyAction.Shift, 1.5f, kind = KeyKind.FUNCTION)
    private val backspace = KeySpec("⌫", KeyAction.Backspace, 1.5f, kind = KeyKind.FUNCTION, repeatable = true)
    private val symbols = KeySpec("?123", KeyAction.Symbols, 1.3f, kind = KeyKind.FUNCTION)
    private val language = KeySpec(
        "한/영", KeyAction.ToggleLanguage, 1.2f, kind = KeyKind.FUNCTION,
        longPress = KeyAction.SwitchKoreanLayout, hint = "길게: 자판",
    )
    private val space = KeySpec("스페이스", KeyAction.Space, 4f, kind = KeyKind.SPACE)
    private val enter = KeySpec("↵", KeyAction.Enter, 1.5f, kind = KeyKind.ACCENT)
    private val emoji = KeySpec("😊", KeyAction.ShowEmoji, 1f, kind = KeyKind.FUNCTION)

    // Same total weight as before the emoji key existed (10f); the space bar gives up the width.
    private val bottomRow = listOf(symbols, language, emoji, text(","), space.copy(weight = 3f), text("."), enter)

    val DUBEOLSIK = KeyboardLayout(
        LayoutId.DUBEOLSIK,
        listOf(
            withDigit(
                listOf(
                    jamo('ㅂ', 'ㅃ'), jamo('ㅈ', 'ㅉ'), jamo('ㄷ', 'ㄸ'), jamo('ㄱ', 'ㄲ'), jamo('ㅅ', 'ㅆ'),
                    jamo('ㅛ'), jamo('ㅕ'), jamo('ㅑ'), jamo('ㅐ', 'ㅒ'), jamo('ㅔ', 'ㅖ'),
                ),
            ),
            listOf(spacer(0.5f)) + "ㅁㄴㅇㄹㅎㅗㅓㅏㅣ".map { jamo(it) } + spacer(0.5f),
            listOf(shift) + "ㅋㅌㅊㅍㅠㅜㅡ".map { jamo(it) } + backspace,
            bottomRow,
        ),
    )

    val QWERTY = KeyboardLayout(
        LayoutId.QWERTY,
        listOf(
            withDigit("qwertyuiop".map(::letter)),
            listOf(spacer(0.5f)) + "asdfghjkl".map(::letter) + spacer(0.5f),
            listOf(shift) + "zxcvbnm".map(::letter) + backspace,
            bottomRow,
        ),
    )

    val SYMBOLS = KeyboardLayout(
        LayoutId.SYMBOLS,
        listOf(
            "1234567890".map { text(it.toString()) },
            listOf("@", "#", "₩", "%", "&", "*", "-", "+", "(", ")").map { text(it) },
            listOf("=", "\"", "'", ":", ";", "!", "?", "/").map { text(it) } + backspace,
            listOf(
                KeySpec("가/A", KeyAction.Letters, 1.3f, kind = KeyKind.FUNCTION),
                emoji,
                text("~"),
                text(","),
                space.copy(weight = 3f),
                text("."),
                enter,
            ),
        ),
    )

    private fun cj(key: CheonjiinConsonantKey) = KeySpec(key.label, KeyAction.CjConsonant(key))
    private fun stroke(s: CheonjiinStroke) = KeySpec(s.symbol.toString(), KeyAction.CjStroke(s))

    val CHEONJIIN = KeyboardLayout(
        LayoutId.CHEONJIIN,
        listOf(
            listOf(
                stroke(CheonjiinStroke.I), stroke(CheonjiinStroke.DOT), stroke(CheonjiinStroke.EU),
                backspace.copy(weight = 1f),
            ),
            listOf(
                cj(CheonjiinConsonantKey.GIYEOK), cj(CheonjiinConsonantKey.NIEUN), cj(CheonjiinConsonantKey.DIGEUT),
                symbols.copy(weight = 1f),
            ),
            listOf(
                cj(CheonjiinConsonantKey.BIEUP), cj(CheonjiinConsonantKey.SIOT), cj(CheonjiinConsonantKey.JIEUT),
                enter.copy(weight = 1f),
            ),
            listOf(
                KeySpec(
                    ".,?!", KeyAction.CjPunctuation,
                    longPress = KeyAction.ShowEmoji, hint = "길게: 😊",
                ),
                cj(CheonjiinConsonantKey.IEUNG),
                space.copy(label = "␣", weight = 1f),
                language.copy(weight = 1f),
            ),
        ),
    )

    fun get(id: LayoutId): KeyboardLayout = when (id) {
        LayoutId.DUBEOLSIK -> DUBEOLSIK
        LayoutId.CHEONJIIN -> CHEONJIIN
        LayoutId.QWERTY -> QWERTY
        LayoutId.SYMBOLS -> SYMBOLS
    }
}
