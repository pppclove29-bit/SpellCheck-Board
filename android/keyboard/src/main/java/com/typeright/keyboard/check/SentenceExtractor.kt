package com.typeright.keyboard.check

/** A span of the text before the cursor. [start] is the index of [text] inside the source string. */
data class SentenceSpan(val text: String, val start: Int)

/**
 * Check target selection (docs/api-contract.md): the last sentence within at most 300 chars before the cursor,
 * with sentences delimited by `. ! ? \n`.
 *
 * Trailing terminators/whitespace belong to the sentence they close, so right after typing `?` the target is the
 * sentence that just ended (e.g. "안녕. 오늘 몇일이야?" → "오늘 몇일이야?"). Leading whitespace is skipped.
 */
object SentenceExtractor {
    const val MAX_WINDOW = 300
    private const val TERMINATORS = ".!?\n"

    fun isTerminator(c: Char): Boolean = TERMINATORS.indexOf(c) >= 0

    /** True when the character right before the cursor ends a sentence (`.` `!` `?` newline). */
    fun endsSentence(textBeforeCursor: CharSequence): Boolean =
        textBeforeCursor.isNotEmpty() && isTerminator(textBeforeCursor[textBeforeCursor.length - 1])

    fun extract(textBeforeCursor: CharSequence): SentenceSpan {
        val text = textBeforeCursor
        val len = text.length
        var windowStart = maxOf(0, len - MAX_WINDOW)
        // Never start in the middle of a surrogate pair.
        if (windowStart in 1 until len && Character.isLowSurrogate(text[windowStart])) windowStart++

        var contentEnd = len
        while (contentEnd > windowStart && (isTerminator(text[contentEnd - 1]) || text[contentEnd - 1].isWhitespace())) {
            contentEnd--
        }

        var start = windowStart
        for (i in contentEnd - 1 downTo windowStart) {
            if (isTerminator(text[i])) {
                start = i + 1
                break
            }
        }
        while (start < contentEnd && text[start].isWhitespace()) start++

        return SentenceSpan(text.subSequence(start, len).toString(), start)
    }
}
