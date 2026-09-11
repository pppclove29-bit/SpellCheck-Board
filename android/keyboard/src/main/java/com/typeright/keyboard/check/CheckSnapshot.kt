package com.typeright.keyboard.check

import com.typeright.keyboard.rules.Correction

/**
 * The exact text that was sent to a check (local or remote) and its absolute start position in the editor.
 * Result offsets are relative to [text].
 */
data class CheckSnapshot(val text: String, val absStart: Int) {
    val absEnd: Int get() = absStart + text.length
}

/**
 * Stale-response guard: a result may only be shown/applied while the snapshot text is still present, unchanged,
 * at the same absolute position before the cursor. Typing after the sentence is fine; editing it is not.
 */
object StaleGuard {
    /**
     * @param textBeforeCursor current text before the cursor (must reach back at least to [CheckSnapshot.absStart]
     *   to be able to confirm freshness)
     * @param cursorAbs current absolute cursor position (selection start)
     */
    fun isFresh(snapshot: CheckSnapshot, textBeforeCursor: CharSequence, cursorAbs: Int): Boolean {
        if (cursorAbs < 0) return false
        val windowStart = cursorAbs - textBeforeCursor.length
        val rel = snapshot.absStart - windowStart
        if (rel < 0 || snapshot.absEnd > cursorAbs) return false
        return textBeforeCursor.regionMatches(rel, snapshot.text, 0, snapshot.text.length)
    }

    /** How many chars before the cursor must be read to validate [snapshot]. */
    fun requiredLookback(snapshot: CheckSnapshot, cursorAbs: Int): Int = cursorAbs - snapshot.absStart
}

/** Absolute replacement to perform in the editor plus where the cursor should end up. */
data class ReplacePlan(val start: Int, val end: Int, val replacement: String, val newCursor: Int)

object CorrectionApplier {
    /** Plans replacing [length] chars at snapshot-relative [offset] with [replacement], keeping the cursor stable. */
    fun plan(snapshot: CheckSnapshot, offset: Int, length: Int, replacement: String, cursorAbs: Int): ReplacePlan {
        val start = snapshot.absStart + offset
        val end = start + length
        val delta = replacement.length - length
        val newCursor = when {
            cursorAbs >= end -> cursorAbs + delta
            cursorAbs > start -> start + replacement.length
            else -> cursorAbs
        }
        return ReplacePlan(start, end, replacement, newCursor)
    }

    /**
     * After [applied] was committed, returns the snapshot as it now reads in the editor and the other corrections
     * shifted by the length delta (overlapping ones are dropped). Keeps later chips valid without a re-check.
     */
    fun rebase(
        snapshot: CheckSnapshot,
        applied: Correction,
        others: List<Correction>,
    ): Pair<CheckSnapshot, List<Correction>> {
        val newText = snapshot.text.replaceRange(applied.offset, applied.end, applied.suggestedWord)
        val delta = applied.suggestedWord.length - applied.length
        val rebased = others.mapNotNull { c ->
            when {
                c == applied -> null
                c.end <= applied.offset -> c
                c.offset >= applied.end -> c.copy(offset = c.offset + delta)
                else -> null
            }
        }
        return snapshot.copy(text = newText) to rebased
    }
}
