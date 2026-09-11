package com.typeright.keyboard.share

import android.content.Intent
import com.typeright.keyboard.rules.FeedbackMode

/**
 * "📸 짤 생성" request: the checked sentence, its first correction and the 훈수 line. Passed by Intent extras from
 * the keyboard (explicit intent into the host app's ShareCardActivity) and from the text-selection checker.
 */
data class ShareCardRequest(
    val sentence: String,
    val errorOffset: Int,
    val errorLength: Int,
    val suggested: String,
    val feedback: String,
    val mode: FeedbackMode,
) {
    fun putInto(intent: Intent): Intent = intent
        .putExtra(EXTRA_SENTENCE, sentence)
        .putExtra(EXTRA_OFFSET, errorOffset)
        .putExtra(EXTRA_LENGTH, errorLength)
        .putExtra(EXTRA_SUGGESTED, suggested)
        .putExtra(EXTRA_FEEDBACK, feedback)
        .putExtra(EXTRA_MODE, mode.apiValue)

    companion object {
        private const val EXTRA_SENTENCE = "typeright.share.sentence"
        private const val EXTRA_OFFSET = "typeright.share.offset"
        private const val EXTRA_LENGTH = "typeright.share.length"
        private const val EXTRA_SUGGESTED = "typeright.share.suggested"
        private const val EXTRA_FEEDBACK = "typeright.share.feedback"
        private const val EXTRA_MODE = "typeright.share.mode"

        fun from(intent: Intent): ShareCardRequest? {
            val sentence = intent.getStringExtra(EXTRA_SENTENCE) ?: return null
            val suggested = intent.getStringExtra(EXTRA_SUGGESTED) ?: return null
            val feedback = intent.getStringExtra(EXTRA_FEEDBACK) ?: return null
            val offset = intent.getIntExtra(EXTRA_OFFSET, -1)
            val length = intent.getIntExtra(EXTRA_LENGTH, 0)
            if (offset < 0 || length <= 0 || offset + length > sentence.length) return null
            val mode = FeedbackMode.fromApi(intent.getStringExtra(EXTRA_MODE)) ?: FeedbackMode.DEFAULT
            return ShareCardRequest(sentence, offset, length, suggested, feedback, mode)
        }
    }
}

/** Chat-bubble text `before + original + suggested + after`; [strike] marks the original, [fix] the correction. */
data class CardBubble(val text: String, val strikeStart: Int, val strikeEnd: Int, val fixStart: Int, val fixEnd: Int)

/** Pure card content (unit-tested); drawing lives in the host app's ShareCardRenderer. */
object ShareCardContent {
    /** Characters of context kept on each side of the error; longer sentences are cut with "…". */
    const val MAX_CONTEXT = 24

    fun bubble(req: ShareCardRequest): CardBubble {
        // Masking keeps the length, so the error offsets stay valid.
        val s = CardPrivacy.mask(req.sentence)
        val start = req.errorOffset
        val end = start + req.errorLength
        val beforeStart = (start - MAX_CONTEXT).coerceAtLeast(0)
        val afterEnd = (end + MAX_CONTEXT).coerceAtMost(s.length)
        val before = (if (beforeStart > 0) "…" else "") + s.substring(beforeStart, start)
        val original = s.substring(start, end)
        val fix = CardPrivacy.mask(req.suggested)
        val after = s.substring(end, afterEnd) + if (afterEnd < s.length) "…" else ""
        val strikeStart = before.length
        val fixStart = strikeStart + original.length
        return CardBubble(before + original + fix + after, strikeStart, fixStart, fixStart, fixStart + fix.length)
    }

    fun feedback(req: ShareCardRequest): String = CardPrivacy.mask(req.feedback)
}

/**
 * The card is shared publicly, so personal data that slipped into the sentence is masked with '*' (same length):
 * phone numbers keep their 01x prefix; emails keep the first character; digit runs of 9+ digits (RRN, card and
 * account numbers) are fully masked.
 */
object CardPrivacy {
    private val PHONE = Regex("(?<![0-9])01[016789][- .]?[0-9]{3,4}[- .]?[0-9]{4}(?![0-9])")
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    // A '-' or ' ' only joins digit groups when 2+ digits follow, so "2026-09-11 3시" stays one 8-digit run (a date,
    // not masked) while "123-45-678901" or "1234 5678 9012 3456" become one sensitive run.
    private val DIGIT_RUN = Regex("[0-9](?:[0-9]|[- ](?=[0-9]{2}))*[0-9]")
    private const val MIN_SENSITIVE_DIGITS = 9

    fun mask(text: String): String {
        val chars = text.toCharArray()
        val phones = PHONE.findAll(text).map { it.range }.toList()
        for (r in phones) for (i in r) if (i >= r.first + 3 && chars[i].isDigit()) chars[i] = '*'
        for (m in EMAIL.findAll(text)) for (i in m.range) if (i > m.range.first && chars[i] != '@' && chars[i] != '.') chars[i] = '*'
        for (m in DIGIT_RUN.findAll(text)) {
            val r = m.range
            if (phones.any { it.first <= r.last && r.first <= it.last }) continue
            if (m.value.count(Char::isDigit) >= MIN_SENSITIVE_DIGITS) for (i in r) if (chars[i].isDigit()) chars[i] = '*'
        }
        return String(chars)
    }
}
