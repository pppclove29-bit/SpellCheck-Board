package com.typeright.keyboard.rules

enum class CorrectionSource(val apiValue: String) {
    RULE("rule"),
    AI("ai");

    companion object {
        fun fromApi(value: String?): CorrectionSource = entries.firstOrNull { it.apiValue == value } ?: RULE
    }
}

enum class SuggestionType(val apiValue: String, val label: String) {
    SPELLING("spelling", "맞춤법"),
    SPACING("spacing", "띄어쓰기"),
    GRAMMAR("grammar", "문법"),
    WORD_CHOICE("word_choice", "단어 선택");

    companion object {
        fun fromApi(value: String?): SuggestionType? = entries.firstOrNull { it.apiValue == value }
    }
}

/** A single correction. offset/length are UTF-16 code units relative to the checked text (Kotlin String indices). */
data class Correction(
    val offset: Int,
    val length: Int,
    val originalWord: String,
    val suggestedWord: String,
    val reason: String,
    val type: SuggestionType = SuggestionType.SPELLING,
    val source: CorrectionSource = CorrectionSource.RULE,
    /** Rule-specific spicy_wit line (on-device rules only). */
    val wit: String? = null,
) {
    val end: Int get() = offset + length
}

data class DictionaryRule(val from: String, val to: String, val reason: String, val wit: String? = null)

data class PatternRule(
    val id: String,
    val pattern: String,
    val replacement: String,
    val reason: String,
    val wit: String? = null,
)

data class FeedbackTemplates(val spicyWit: String, val police: String, val gentle: String) {
    fun forMode(mode: FeedbackMode): String = when (mode) {
        FeedbackMode.SPICY_WIT -> spicyWit
        FeedbackMode.POLICE -> police
        FeedbackMode.GENTLE -> gentle
    }

    companion object {
        val EMPTY = FeedbackTemplates("", "", "")
    }
}

data class RulesFile(
    val version: Int,
    val feedbackTemplates: FeedbackTemplates,
    val dictionary: List<DictionaryRule>,
    val patterns: List<PatternRule>,
)
