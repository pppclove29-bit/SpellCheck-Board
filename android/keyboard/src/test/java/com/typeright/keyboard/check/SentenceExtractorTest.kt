package com.typeright.keyboard.check

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceExtractorTest {

    @Test
    fun singleSentence() {
        assertEquals(SentenceSpan("오늘 몇일이야?", 0), SentenceExtractor.extract("오늘 몇일이야?"))
    }

    @Test
    fun sentenceThatJustEndedIsTheTarget() {
        assertEquals(SentenceSpan("오늘 몇일이야?", 4), SentenceExtractor.extract("안녕. 오늘 몇일이야?"))
    }

    @Test
    fun sentenceBeingTyped() {
        assertEquals(SentenceSpan("반가워", 4), SentenceExtractor.extract("안녕. 반가워"))
        assertEquals(SentenceSpan("반가워 ", 4), SentenceExtractor.extract("안녕! 반가워 "))
    }

    @Test
    fun newlineIsABoundary() {
        assertEquals(SentenceSpan("둘째 줄", 3), SentenceExtractor.extract("첫줄\n둘째 줄"))
        assertEquals(SentenceSpan("첫줄\n", 0), SentenceExtractor.extract("첫줄\n"))
    }

    @Test
    fun trailingSpaceAfterTerminatorBelongsToPreviousSentence() {
        assertEquals(SentenceSpan("질문? ", 0), SentenceExtractor.extract("질문? "))
        assertEquals(SentenceSpan("둘?! ", 4), SentenceExtractor.extract("하나. 둘?! "))
    }

    @Test
    fun emptyAndTerminatorOnly() {
        assertEquals(SentenceSpan("", 0), SentenceExtractor.extract(""))
        assertEquals(SentenceSpan("...", 0), SentenceExtractor.extract("..."))
    }

    @Test
    fun windowIsLimitedTo300Chars() {
        val text = "가".repeat(400)
        val span = SentenceExtractor.extract(text)
        assertEquals(100, span.start)
        assertEquals(300, span.text.length)
    }

    @Test
    fun windowNeverStartsInsideSurrogatePair() {
        // 302 UTF-16 units: the 300-unit window would start at index 2, the low half of the first emoji (1..2).
        val text = "a" + "😀".repeat(150) + "b"
        val span = SentenceExtractor.extract(text)
        assertEquals(3, span.start)
        assertTrue(Character.isHighSurrogate(span.text[0]))
    }

    @Test
    fun endsSentence() {
        assertTrue(SentenceExtractor.endsSentence("abc?"))
        assertTrue(SentenceExtractor.endsSentence("abc."))
        assertTrue(SentenceExtractor.endsSentence("abc!"))
        assertTrue(SentenceExtractor.endsSentence("줄\n"))
        assertFalse(SentenceExtractor.endsSentence("abc? "))
        assertFalse(SentenceExtractor.endsSentence(""))
    }
}
