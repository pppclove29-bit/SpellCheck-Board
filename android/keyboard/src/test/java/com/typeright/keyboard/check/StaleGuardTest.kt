package com.typeright.keyboard.check

import com.typeright.keyboard.rules.Correction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleGuardTest {
    private val prefix = "안녕하세요. " // 7 chars
    private val sentence = "오늘 몇일이야?"
    private val snap = CheckSnapshot(sentence, absStart = prefix.length)

    @Test
    fun freshWhenUnchangedAtSamePosition() {
        val doc = prefix + sentence
        assertTrue(StaleGuard.isFresh(snap, doc, doc.length))
    }

    @Test
    fun textTypedAfterTheSentenceKeepsItFresh() {
        val doc = "$prefix$sentence 그리고"
        assertTrue(StaleGuard.isFresh(snap, doc, doc.length))
        // Only the tail of the document was read: still enough to reach absStart.
        val tail = doc.substring(3)
        assertTrue(StaleGuard.isFresh(snap, tail, doc.length))
    }

    @Test
    fun editInsideTheSentenceMakesItStale() {
        val doc = prefix + "오늘 며칠이야?"
        assertFalse(StaleGuard.isFresh(snap, doc, doc.length))
    }

    @Test
    fun positionShiftMakesItStale() {
        val doc = "!" + prefix + sentence // same text, shifted by one
        assertFalse(StaleGuard.isFresh(snap, doc, doc.length))
    }

    @Test
    fun cursorInsideOrBeforeSentenceIsStale() {
        val doc = prefix + sentence
        assertFalse(StaleGuard.isFresh(snap, doc.substring(0, doc.length - 1), doc.length - 1))
    }

    @Test
    fun windowThatDoesNotReachStartIsStale() {
        val doc = prefix + sentence
        assertFalse(StaleGuard.isFresh(snap, doc.substring(prefix.length + 1), doc.length))
        assertFalse(StaleGuard.isFresh(snap, doc, -1))
    }

    @Test
    fun requiredLookback() {
        assertEquals(sentence.length + 3, StaleGuard.requiredLookback(snap, snap.absEnd + 3))
    }
}

class CorrectionApplierTest {

    @Test
    fun planKeepsCursorStableWithLengthDelta() {
        val snap = CheckSnapshot("나도 할수있어", absStart = 10)
        val plan = CorrectionApplier.plan(snap, offset = 3, length = 3, replacement = "할 수 있", cursorAbs = 20)
        assertEquals(ReplacePlan(13, 16, "할 수 있", 22), plan)
    }

    @Test
    fun planWithCursorInsideReplacedRegionMovesToItsEnd() {
        val snap = CheckSnapshot("몇일", absStart = 0)
        assertEquals(ReplacePlan(0, 2, "며칠", 2), CorrectionApplier.plan(snap, 0, 2, "며칠", cursorAbs = 1))
    }

    @Test
    fun planWithCursorBeforeRegionLeavesCursor() {
        val snap = CheckSnapshot("오늘 몇일", absStart = 5)
        assertEquals(4, CorrectionApplier.plan(snap, 3, 2, "며칠", cursorAbs = 4).newCursor)
    }

    @Test
    fun rebaseShiftsLaterCorrections() {
        val snap = CheckSnapshot("먹을때 연락할께", absStart = 0)
        val first = Correction(1, 2, "을때", "을 때", "r")
        val second = Correction(6, 2, "할께", "할게", "r")
        val (newSnap, rest) = CorrectionApplier.rebase(snap, first, listOf(first, second))
        assertEquals("먹을 때 연락할께", newSnap.text)
        assertEquals(listOf(second.copy(offset = 7)), rest)
        assertEquals("할께", newSnap.text.substring(rest[0].offset, rest[0].end))
    }

    @Test
    fun rebaseKeepsEarlierCorrections() {
        val snap = CheckSnapshot("먹을때 연락할께", absStart = 0)
        val first = Correction(1, 2, "을때", "을 때", "r")
        val second = Correction(6, 2, "할께", "할게", "r")
        val (newSnap, rest) = CorrectionApplier.rebase(snap, second, listOf(first, second))
        assertEquals("먹을때 연락할게", newSnap.text)
        assertEquals(listOf(first), rest)
    }
}
