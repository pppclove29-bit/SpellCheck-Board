package com.typeright.keyboard.rules

/** 피드백 모드 (docs/api-contract.md "피드백 모드"). [apiValue] is the `/v1/grammar-check` `mode`. */
enum class FeedbackMode(val apiValue: String, val label: String, val description: String) {
    SPICY_WIT("spicy_wit", "매운맛 훈수", "유쾌한 팩트폭격 한 문장을 말풍선으로 보여줘요."),
    POLICE("police", "맞춤법 경찰", "오류를 찾으면 사이렌 진동! PRO는 교정 전까지 스페이스·엔터·문장부호 입력을 막아요."),
    GENTLE("gentle", "상냥한 선생님", "정중한 설명과 문법 원리 팁을 카드로 보여줘요.");

    companion object {
        val DEFAULT = SPICY_WIT

        fun fromApi(value: String?): FeedbackMode? = entries.firstOrNull { it.apiValue == value }
    }
}

/** On-device feedback algorithm and helpers. Mirrors backend/src/utils/feedback.ts exactly. */
object Feedback {

    /** Rule suggestion type: equal once (JS `\s`) whitespace is removed → "spacing", else "spelling". */
    fun ruleSuggestionType(original: String, suggested: String): SuggestionType =
        if (stripWhitespace(original) == stripWhitespace(suggested)) SuggestionType.SPACING else SuggestionType.SPELLING

    /**
     * 1. First adopted correction (smallest offset); none → null.
     * 2. spicy_wit: the rule's `wit` if present (non-empty), else `feedback_templates.spicy_wit`.
     * 3. police / gentle: their template.
     * 4. Replace `{original}` `{suggested}` `{reason}` (in that order, all occurrences).
     */
    fun compose(corrections: List<Correction>, mode: FeedbackMode, templates: FeedbackTemplates): String? {
        val first = corrections.firstOrNull() ?: return null
        if (mode == FeedbackMode.SPICY_WIT && !first.wit.isNullOrEmpty()) return first.wit
        return templates.forMode(mode)
            .replace("{original}", first.originalWord)
            .replace("{suggested}", first.suggestedWord)
            .replace("{reason}", first.reason)
    }

    /** Applies non-overlapping corrections (any order) to [text]. */
    fun applyCorrections(text: String, corrections: List<Correction>): String =
        corrections.sortedByDescending { it.offset }.fold(text) { acc, c ->
            acc.substring(0, c.offset) + c.suggestedWord + acc.substring(c.end)
        }

    /** ECMAScript WhiteSpace + LineTerminator (exactly what JS `\s` matches). */
    fun isJsWhitespace(c: Char): Boolean = when (c.code) {
        0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF -> true
        in 0x2000..0x200A -> true
        else -> false
    }

    private fun stripWhitespace(s: String): String = s.filterNot(::isJsWhitespace)
}
