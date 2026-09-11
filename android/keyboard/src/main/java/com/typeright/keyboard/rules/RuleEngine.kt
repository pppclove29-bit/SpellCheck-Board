package com.typeright.keyboard.rules

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * On-device rule engine. Same algorithm as backend/src/utils/ruleEngine.ts and iOS (docs/api-contract.md):
 *
 * 1. Every `dictionary.from` is searched literally for all occurrences (overlapping allowed).
 * 2. Every `patterns.pattern` is matched on the full text; `suggested_word` = `replacement` with `$0`..`$9`
 *    expanded manually from the match's groups (unmatched group = ""). The regex is never re-run on the isolated
 *    match, because lookbehind/lookahead need the surrounding text.
 * 3. No-op candidates (`suggested == original`) are dropped.
 * 4. Stable sort by (offset asc, length desc) — ties keep generation order (dictionary in file order, then
 *    patterns in file order) — and greedily keep non-overlapping spans.
 *
 * Note: the no-op filter runs before the overlap selection, exactly like the backend reference implementation.
 */
class RuleEngine(val rules: RulesFile) {
    private val dictionary: List<DictionaryRule> = rules.dictionary.filter { it.from.isNotEmpty() }
    private val patterns: List<Pair<PatternRule, Pattern>> =
        rules.patterns.map { it to Pattern.compile(JsRegexCompat.toJavaPattern(it.pattern)) }

    val feedbackTemplates: FeedbackTemplates get() = rules.feedbackTemplates

    fun check(text: String): List<Correction> {
        if (text.isEmpty()) return emptyList()
        val candidates = ArrayList<Correction>()

        for (d in dictionary) {
            var i = text.indexOf(d.from)
            while (i != -1) {
                candidates += rule(i, d.from, d.to, d.reason, d.wit)
                i = text.indexOf(d.from, i + 1)
            }
        }

        for ((p, regex) in patterns) {
            val m = regex.matcher(text)
            while (m.find()) {
                val start = m.start()
                val end = m.end()
                if (end == start) continue
                candidates += rule(start, text.substring(start, end), expandReplacement(p.replacement, m), p.reason, p.wit)
            }
        }

        return selectNonOverlapping(candidates.filter { it.suggestedWord != it.originalWord })
    }

    /** On-device feedback for [corrections] (see [Feedback.compose]). */
    fun feedback(corrections: List<Correction>, mode: FeedbackMode): String? =
        Feedback.compose(corrections, mode, rules.feedbackTemplates)

    private fun rule(offset: Int, original: String, suggested: String, reason: String, wit: String?) = Correction(
        offset = offset,
        length = original.length,
        originalWord = original,
        suggestedWord = suggested,
        reason = reason,
        type = Feedback.ruleSuggestionType(original, suggested),
        source = CorrectionSource.RULE,
        wit = wit,
    )

    companion object {
        /** Expands `$0`..`$9` (single digit, like JS `/\$(\d)/g`) with the match's groups; missing/unmatched = "". */
        fun expandReplacement(replacement: String, match: Matcher): String {
            if (replacement.indexOf('$') < 0) return replacement
            val sb = StringBuilder(replacement.length + 8)
            var i = 0
            while (i < replacement.length) {
                val c = replacement[i]
                val next = if (i + 1 < replacement.length) replacement[i + 1] else null
                if (c == '$' && next != null && next in '0'..'9') {
                    val group = next - '0'
                    if (group <= match.groupCount()) sb.append(match.group(group) ?: "")
                    i += 2
                } else {
                    sb.append(c)
                    i++
                }
            }
            return sb.toString()
        }

        /** Stable sort by (offset asc, length desc), then greedily keep non-overlapping spans. */
        fun selectNonOverlapping(candidates: List<Correction>): List<Correction> {
            val sorted = candidates.sortedWith(compareBy<Correction> { it.offset }.thenByDescending { it.length })
            val selected = ArrayList<Correction>(sorted.size)
            var end = 0
            for (c in sorted) {
                if (c.offset >= end) {
                    selected += c
                    end = c.end
                }
            }
            return selected
        }
    }
}
