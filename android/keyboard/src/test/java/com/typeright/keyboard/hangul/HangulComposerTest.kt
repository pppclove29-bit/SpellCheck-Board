package com.typeright.keyboard.hangul

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class HangulComposerTest {

    /** Types [jamo] and returns everything committed so far followed by the composing text. */
    private fun HangulComposer.type(jamo: String): String {
        val out = StringBuilder()
        for (c in jamo) out.append(input(c).commit)
        return out.toString() + composingText
    }

    private fun typed(jamo: String) = HangulComposer().type(jamo)

    @Test
    fun simpleSyllablesAndWords() {
        assertEquals("가", typed("ㄱㅏ"))
        assertEquals("각", typed("ㄱㅏㄱ"))
        assertEquals("한글", typed("ㅎㅏㄴㄱㅡㄹ"))
        assertEquals("안녕하세요", typed("ㅇㅏㄴㄴㅕㅇㅎㅏㅅㅔㅇㅛ"))
    }

    @Test
    fun doubleConsonantsFromShift() {
        assertEquals("싸", typed("ㅆㅏ"))
        assertEquals("빵", typed("ㅃㅏㅇ"))
        assertEquals("있다", typed("ㅇㅣㅆㄷㅏ"))
    }

    @Test
    fun compoundVowels() {
        mapOf(
            "ㄱㅗㅏ" to "과", "ㄱㅗㅐ" to "괘", "ㄱㅗㅣ" to "괴", "ㄱㅜㅓ" to "궈",
            "ㄱㅜㅔ" to "궤", "ㄱㅜㅣ" to "귀", "ㅇㅡㅣ" to "의",
        ).forEach { (input, expected) -> assertEquals(input, expected, typed(input)) }
    }

    @Test
    fun everyCompoundFinalConsonant() {
        Hangul.FINAL_COMBINATIONS.forEach { (pair, compound) ->
            assertEquals(
                "ㄱㅏ${pair.first}${pair.second}",
                Hangul.compose('ㄱ', 'ㅏ', compound).toString(),
                typed("ㄱㅏ${pair.first}${pair.second}"),
            )
        }
        assertEquals("닭", typed("ㄷㅏㄹㄱ"))
        assertEquals("없다", typed("ㅇㅓㅂㅅㄷㅏ"))
        assertEquals("앉아", typed("ㅇㅏㄴㅈㅇㅏ"))
        assertEquals("많이", typed("ㅁㅏㄴㅎㅇㅣ"))
    }

    @Test
    fun finalConsonantMovesToNextSyllableOnVowel() {
        assertEquals("가바", typed("ㄱㅏㅂㅏ"))
        assertEquals("갑사", typed("ㄱㅏㅂㅅㅏ"))
        assertEquals("달기", typed("ㄷㅏㄹㄱㅣ"))
        assertEquals("가싸", typed("ㄱㅏㅆㅏ"))
    }

    @Test
    fun vowelSplitCommitsPreviousSyllable() {
        val c = HangulComposer()
        c.input('ㄱ')
        c.input('ㅏ')
        assertEquals(ComposeResult("", "갑"), c.input('ㅂ'))
        assertEquals(ComposeResult("가", "바"), c.input('ㅏ'))
    }

    @Test
    fun consonantThatCannotBeFinalStartsNewSyllable() {
        val c = HangulComposer()
        assertEquals("가ㄸ", c.type("ㄱㅏㄸ"))
        assertEquals(ComposeResult("가", "따"), c.input('ㅏ'))
    }

    @Test
    fun consonantClustersAreNotCombinedAsInitials() {
        assertEquals("ㄱㅅ", typed("ㄱㅅ"))
        assertEquals("ㅈㅅ", typed("ㅈㅅ"))
    }

    @Test
    fun loneVowels() {
        assertEquals("ㅏㅏ", typed("ㅏㅏ"))
        assertEquals("ㅘ", typed("ㅗㅏ"))
    }

    @Test
    fun backspaceDecomposesJamoByJamo() {
        val c = HangulComposer()
        assertEquals("값", c.type("ㄱㅏㅂㅅ"))
        listOf("갑", "가", "ㄱ", "").forEach { assertEquals(it, c.backspace()!!.composing) }
        assertNull(c.backspace())
    }

    @Test
    fun backspaceSplitsCompoundVowel() {
        val c = HangulComposer()
        assertEquals("과", c.type("ㄱㅗㅏ"))
        assertEquals("고", c.backspace()!!.composing)
        assertEquals("ㄱ", c.backspace()!!.composing)
    }

    @Test
    fun backspaceReturnsIntoKeptSyllable() {
        val c = HangulComposer()
        assertEquals("갈ㅆ", c.type("ㄱㅏㄹㅆ"))
        assertEquals("갈", c.backspace()!!.composing)
        assertEquals("가", c.backspace()!!.composing)
    }

    @Test
    fun finishCommitsEverything() {
        val c = HangulComposer()
        c.type("ㄱㅏㄹㅆ")
        assertEquals(ComposeResult("갈ㅆ", ""), c.finish())
        assertFalse(c.isComposing)
    }

    @Test
    fun nonJamoCommitsComposingAndItself() {
        val c = HangulComposer()
        c.type("ㄱㅏ")
        assertEquals(ComposeResult("가.", ""), c.input('.'))
    }
}
