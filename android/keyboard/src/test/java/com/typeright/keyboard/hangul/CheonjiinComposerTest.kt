package com.typeright.keyboard.hangul

import org.junit.Assert.assertEquals
import org.junit.Test

class CheonjiinComposerTest {
    private val keys = mapOf(
        'ㄱ' to CheonjiinConsonantKey.GIYEOK,
        'ㄴ' to CheonjiinConsonantKey.NIEUN,
        'ㄷ' to CheonjiinConsonantKey.DIGEUT,
        'ㅂ' to CheonjiinConsonantKey.BIEUP,
        'ㅅ' to CheonjiinConsonantKey.SIOT,
        'ㅈ' to CheonjiinConsonantKey.JIEUT,
        'ㅇ' to CheonjiinConsonantKey.IEUNG,
    )
    private val strokes = mapOf('ㅣ' to CheonjiinStroke.I, 'ㆍ' to CheonjiinStroke.DOT, 'ㅡ' to CheonjiinStroke.EU)

    /** Key taps: consonant keys by their first consonant, strokes ㅣㆍㅡ, '|' breaks consonant cycling. */
    private fun CheonjiinComposer.type(seq: String): String {
        val out = StringBuilder()
        for (ch in seq) {
            when (ch) {
                '|' -> breakCycle()
                in keys -> out.append(inputConsonant(keys.getValue(ch)).commit)
                in strokes -> out.append(inputStroke(strokes.getValue(ch)).commit)
                else -> error("unknown key $ch")
            }
        }
        return out.toString() + composingText
    }

    private fun typed(seq: String) = CheonjiinComposer().type(seq)

    private fun assertAll(cases: Map<String, String>) =
        cases.forEach { (input, expected) -> assertEquals(input, expected, typed(input)) }

    @Test
    fun basicVowels() = assertAll(
        mapOf(
            "ㄱㅣ" to "기", "ㄱㅡ" to "그",
            "ㄱㅣㆍ" to "가", "ㄱㆍㅣ" to "거", "ㄱㆍㅡ" to "고", "ㄱㅡㆍ" to "구",
            "ㄱㅣㆍㆍ" to "갸", "ㄱㆍㆍㅣ" to "겨", "ㄱㆍㆍㅡ" to "교", "ㄱㅡㆍㆍ" to "규",
            "ㅇㅣㆍ" to "아",
        ),
    )

    @Test
    fun compoundVowels() = assertAll(
        mapOf(
            "ㄱㅣㆍㅣ" to "개", "ㄱㆍㅣㅣ" to "게", "ㄱㅣㆍㆍㅣ" to "걔", "ㄱㆍㆍㅣㅣ" to "계",
            "ㄱㆍㅡㅣ" to "괴", "ㄱㆍㅡㅣㆍ" to "과", "ㄱㆍㅡㅣㆍㅣ" to "괘",
            "ㄱㅡㆍㅣ" to "귀", "ㄱㅡㆍㆍㅣ" to "궈", "ㄱㅡㆍㆍㅣㅣ" to "궤", "ㄱㅡㅣ" to "긔",
        ),
    )

    @Test
    fun consonantCycling() = assertAll(
        mapOf(
            "ㄱ" to "ㄱ", "ㄱㄱ" to "ㅋ", "ㄱㄱㄱ" to "ㄲ", "ㄱㄱㄱㄱ" to "ㄱ",
            "ㄴㄴ" to "ㄹ", "ㄷㄷ" to "ㅌ", "ㄷㄷㄷ" to "ㄸ", "ㅂㅂ" to "ㅍ", "ㅂㅂㅂ" to "ㅃ",
            "ㅅㅅ" to "ㅎ", "ㅅㅅㅅ" to "ㅆ", "ㅈㅈ" to "ㅊ", "ㅈㅈㅈ" to "ㅉ", "ㅇㅇ" to "ㅁ",
            "ㄱ|ㄱ" to "ㄱㄱ",
        ),
    )

    @Test
    fun cyclingOnFinalConsonant() = assertAll(
        mapOf(
            "ㄱㅣㆍㄱ" to "각", "ㄱㅣㆍㄱㄱ" to "갘", "ㄱㅣㆍㄱㄱㄱ" to "갂",
            "ㄱㅣㆍㄷㄷㄷ" to "가ㄸ", "ㄱㅣㆍㄷㄷㄷㄷ" to "갇",
            "ㄱㅣㆍㄴㄴㅅ" to "갌", "ㄱㅣㆍㄴㄴㅅㅅ" to "갏", "ㄱㅣㆍㄴㄴㅅㅅㅅ" to "갈ㅆ",
        ),
    )

    @Test
    fun words() = assertAll(
        mapOf(
            "ㅅㅅㅣㆍㄴㄱㅡㄴㄴ" to "한글",
            "ㄷㅣㆍㄴㄴㄱ" to "닭",
        ),
    )

    @Test
    fun finalMovesToNextSyllableOnVowel() = assertAll(
        mapOf("ㄱㅣㆍㄱㅣ" to "가기", "ㄱㅣㆍㄱㆍㅣ" to "가거"),
    )

    @Test
    fun pendingDotIsShownAndBackspacedFirst() {
        val c = CheonjiinComposer()
        assertEquals("ㄱㆍ", c.type("ㄱㆍ"))
        assertEquals("ㄱ", c.backspace()!!.composing)
        assertEquals("ㄱㆍㆍ", c.type("ㆍㆍ"))
    }

    @Test
    fun danglingDotIsDroppedByConsonant() {
        assertEquals("ㄱㄴ", typed("ㄱㆍㄴ"))
    }

    @Test
    fun backspaceRemovesWholeBuiltVowel() {
        val c = CheonjiinComposer()
        assertEquals("과", c.type("ㄱㆍㅡㅣㆍ"))
        assertEquals("ㄱ", c.backspace()!!.composing)
    }

    @Test
    fun finishDropsPendingDot() {
        val c = CheonjiinComposer()
        c.type("ㄱㅣㆍㆍ")
        c.inputStroke(CheonjiinStroke.DOT) // (ㅑ, ㆍ) has no combination → pending ㆍ
        assertEquals(ComposeResult("갸", ""), c.finish())
    }
}
