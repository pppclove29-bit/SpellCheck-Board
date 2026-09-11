package com.typeright.keyboard.check

import com.typeright.keyboard.rules.Correction
import com.typeright.keyboard.rules.FeedbackMode

/** Absolute identity of a correction span in the editor. */
data class SpanKey(val absOffset: Int, val word: String) {
    companion object {
        fun of(snapshot: CheckSnapshot, c: Correction) = SpanKey(snapshot.absStart + c.offset, c.originalWord)
    }
}

/**
 * 맞춤법 경찰 state for the current sentence (pure logic, unit-tested):
 * - '무시' turns police mode OFF for the whole current sentence: no blocking, no siren. Correction chips still show.
 *   The key is the sentence's absolute start; it resets when a new sentence starts or the start moves.
 * - [shouldBlock]: police mode + PRO + not secure + sentence not ignored + ≥1 un-applied correction
 *   → space/enter/`. ! ?` are swallowed.
 * - [takeNewlyDetected]: errors not alerted before (the siren fires once per new error, not per re-check).
 */
class PoliceGate {
    private var sentenceStart = Int.MIN_VALUE
    private var ignoredSentenceStart: Int? = null
    private val alerted = HashSet<SpanKey>()

    fun enterSentence(absStart: Int) {
        if (absStart != sentenceStart) {
            sentenceStart = absStart
            ignoredSentenceStart = null
            alerted.clear()
        }
    }

    /** '무시': police off for the sentence starting at [absStart]. */
    fun ignoreSentence(absStart: Int) {
        enterSentence(absStart)
        ignoredSentenceStart = absStart
    }

    fun isSentenceIgnored(absStart: Int): Boolean = ignoredSentenceStart == absStart

    fun shouldBlock(
        mode: FeedbackMode,
        isPro: Boolean,
        secure: Boolean,
        snapshot: CheckSnapshot?,
        corrections: List<Correction>,
    ): Boolean {
        if (mode != FeedbackMode.POLICE || !isPro || secure || snapshot == null) return false
        return !isSentenceIgnored(snapshot.absStart) && corrections.isNotEmpty()
    }

    /** Returns the corrections that were not alerted yet and marks them alerted (none while the sentence is ignored). */
    fun takeNewlyDetected(snapshot: CheckSnapshot, corrections: List<Correction>): List<Correction> {
        if (isSentenceIgnored(snapshot.absStart)) return emptyList()
        return corrections.filter { alerted.add(SpanKey.of(snapshot, it)) }
    }

    fun reset() {
        sentenceStart = Int.MIN_VALUE
        ignoredSentenceStart = null
        alerted.clear()
    }
}
