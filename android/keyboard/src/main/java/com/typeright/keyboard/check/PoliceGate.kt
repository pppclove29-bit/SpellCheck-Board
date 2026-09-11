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
 * - '무시' adds a span to the ignore list of the current sentence (false-positive escape hatch, always offered).
 * - [shouldBlock]: PRO + police mode + not secure + any non-ignored error → space/enter/`. ! ?` are blocked.
 * - [takeNewlyDetected]: errors not alerted before (so the siren haptic fires once per new error, not per re-check).
 * Both lists reset when the sentence start moves (a new sentence) or on [reset].
 */
class PoliceGate {
    private var sentenceStart = Int.MIN_VALUE
    private val ignored = HashSet<SpanKey>()
    private val alerted = HashSet<SpanKey>()

    fun enterSentence(absStart: Int) {
        if (absStart != sentenceStart) {
            sentenceStart = absStart
            ignored.clear()
            alerted.clear()
        }
    }

    fun ignore(key: SpanKey) {
        ignored += key
    }

    fun isIgnored(key: SpanKey): Boolean = key in ignored

    fun unresolved(snapshot: CheckSnapshot, corrections: List<Correction>): List<Correction> =
        corrections.filterNot { isIgnored(SpanKey.of(snapshot, it)) }

    fun shouldBlock(
        mode: FeedbackMode,
        isPro: Boolean,
        secure: Boolean,
        snapshot: CheckSnapshot?,
        corrections: List<Correction>,
    ): Boolean {
        if (mode != FeedbackMode.POLICE || !isPro || secure || snapshot == null) return false
        return unresolved(snapshot, corrections).isNotEmpty()
    }

    /** Returns the corrections that were not alerted yet and marks them alerted. */
    fun takeNewlyDetected(snapshot: CheckSnapshot, corrections: List<Correction>): List<Correction> =
        unresolved(snapshot, corrections).filter { alerted.add(SpanKey.of(snapshot, it)) }

    fun reset() {
        sentenceStart = Int.MIN_VALUE
        ignored.clear()
        alerted.clear()
    }
}
