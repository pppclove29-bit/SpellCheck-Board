package com.typeright.keyboard.share

import com.typeright.keyboard.rules.FeedbackMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareCardContentTest {
    private fun bubble(sentence: String, offset: Int, length: Int, suggested: String) =
        ShareCardContent.bubble(ShareCardRequest(sentence, offset, length, suggested, "훈수", FeedbackMode.SPICY_WIT))

    @Test
    fun bubbleStrikesTheOriginalAndHighlightsTheFix() {
        val b = bubble("오늘 진짜 어의가 없네", 6, 2, "어이")
        assertEquals("오늘 진짜 어의어이가 없네", b.text)
        assertEquals("어의", b.text.substring(b.strikeStart, b.strikeEnd))
        assertEquals("어이", b.text.substring(b.fixStart, b.fixEnd))
    }

    @Test
    fun longSentencesAreCutAroundTheError() {
        val sentence = "가".repeat(40) + "몇일" + "나".repeat(40)
        val b = bubble(sentence, 40, 2, "며칠")
        assertTrue(b.text.startsWith("…"))
        assertTrue(b.text.endsWith("…"))
        assertEquals("몇일", b.text.substring(b.strikeStart, b.strikeEnd))
        assertEquals(1 + ShareCardContent.MAX_CONTEXT + 2 + 2 + ShareCardContent.MAX_CONTEXT + 1, b.text.length)
    }

    @Test
    fun maskHidesPersonalDataAndKeepsLength() {
        assertEquals("번호 010-****-****로", CardPrivacy.mask("번호 010-1234-5678로"))
        assertEquals("010********", CardPrivacy.mask("01012345678"))
        assertEquals("a**@****.***", CardPrivacy.mask("abc@test.com"))
        assertEquals("******-*******", CardPrivacy.mask("900101-1234567"))
        assertEquals("국민 ***-**-******", CardPrivacy.mask("국민 123-45-678901"))
        assertEquals("**** **** **** ****", CardPrivacy.mask("1234 5678 9012 3456"))
    }

    @Test
    fun maskLeavesOrdinaryNumbersAlone() {
        assertEquals("2026-09-11 3시", CardPrivacy.mask("2026-09-11 3시"))
        assertEquals("10시 30분에 3명", CardPrivacy.mask("10시 30분에 3명"))
    }

    @Test
    fun bubbleMasksPiiButKeepsErrorOffsets() {
        val b = bubble("010-1234-5678로 연락 할께", 18, 2, "할게")
        assertEquals("010-****-****로 연락 할께할게", b.text)
        assertEquals("할께", b.text.substring(b.strikeStart, b.strikeEnd))
    }
}
