package com.typeright.keyboard.rules

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.regex.Pattern

/** Cross-platform golden tests: shared/rule-golden-cases.json against shared/korean-rules.json. */
class RuleEngineGoldenTest {

    private data class Span(val offset: Int, val length: Int, val original: String, val suggested: String)

    /** `../../shared` relative to the module dir (android/keyboard), or the path exported by Gradle. */
    private val sharedDir: File = listOfNotNull(File("../../shared"), System.getProperty("typeright.sharedDir")?.let(::File))
        .first { File(it, "korean-rules.json").isFile }

    private val engine = RuleEngine(RulesParser.parse(File(sharedDir, "korean-rules.json").readText()))
    private val golden = Json.parseToJsonElement(File(sharedDir, "rule-golden-cases.json").readText()).jsonObject

    @Test
    fun allGoldenCasesMatchExactly() {
        val cases = golden.getValue("cases").jsonArray
        assertTrue("golden file has cases", cases.isNotEmpty())
        val failures = cases.mapNotNull { el ->
            val o = el.jsonObject
            val text = o.getValue("text").jsonPrimitive.content
            val expected = o.getValue("expected").jsonArray.map { e ->
                val x = e.jsonObject
                Span(
                    x.getValue("offset").jsonPrimitive.int,
                    x.getValue("length").jsonPrimitive.int,
                    x.getValue("original_word").jsonPrimitive.content,
                    x.getValue("suggested_word").jsonPrimitive.content,
                )
            }
            val actual = engine.check(text).map { Span(it.offset, it.length, it.originalWord, it.suggestedWord) }
            if (expected == actual) null else "\"$text\"\n  expected $expected\n  actual   $actual"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun feedbackCasesMatchExactly() {
        val cases = golden["feedback_cases"]?.jsonArray.orEmpty()
        assertTrue(cases.isNotEmpty())
        for (el in cases) {
            val o = el.jsonObject
            val text = o.getValue("text").jsonPrimitive.content
            val mode = FeedbackMode.fromApi(o.getValue("mode").jsonPrimitive.content)!!
            val expected = o.getValue("expected").let { if (it is JsonNull) null else it.jsonPrimitive.content }
            assertEquals("$mode: $text", expected, engine.feedback(engine.check(text), mode))
        }
    }

    @Test
    fun typeCasesMatchExactly() {
        val cases = golden["type_cases"]?.jsonArray.orEmpty()
        assertTrue(cases.isNotEmpty())
        for (el in cases) {
            val o = el.jsonObject
            val original = o.getValue("original_word").jsonPrimitive.content
            val suggested = o.getValue("suggested_word").jsonPrimitive.content
            val expected = SuggestionType.fromApi(o.getValue("type").jsonPrimitive.content)
            assertEquals("$original → $suggested", expected, Feedback.ruleSuggestionType(original, suggested))
        }
    }

    @Test
    fun ruleResultsCarryTypeAndWit() {
        val c = engine.check("오늘 진짜 어의가 없네").single()
        assertEquals(SuggestionType.SPELLING, c.type)
        assertEquals(CorrectionSource.RULE, c.source)
        assertTrue(!c.wit.isNullOrEmpty())
        assertEquals(SuggestionType.SPACING, engine.check("나도 할수있어").single().type)
    }

    @Test
    fun utf16OffsetsWithSurrogatePairs() {
        // 😀 is two UTF-16 code units.
        val c = engine.check("😀 몇일 남았어?").single()
        assertEquals(3, c.offset)
        assertEquals("몇일", c.originalWord)
    }

    @Test
    fun noOpCandidatesAreDroppedBeforeOverlapSelection() {
        val rules = RulesFile(
            version = 2,
            feedbackTemplates = FeedbackTemplates.EMPTY,
            dictionary = listOf(DictionaryRule("ab", "ab", "no-op"), DictionaryRule("b", "c", "real")),
            patterns = emptyList(),
        )
        val result = RuleEngine(rules).check("ab")
        assertEquals(listOf(Span(1, 1, "b", "c")), result.map { Span(it.offset, it.length, it.originalWord, it.suggestedWord) })
    }

    @Test
    fun tiesKeepGenerationOrderDictionaryFirst() {
        val rules = RulesFile(
            version = 2,
            feedbackTemplates = FeedbackTemplates.EMPTY,
            dictionary = listOf(DictionaryRule("가나", "사전", "d")),
            patterns = listOf(PatternRule("p", "가나", "패턴", "p")),
        )
        assertEquals("사전", RuleEngine(rules).check("가나").single().suggestedWord)
    }

    @Test
    fun longerSpanWinsAtSameOffset() {
        assertEquals("않되요", engine.check("않되요").single().originalWord)
    }

    @Test
    fun lookbehindSeesFullText() {
        assertEquals(1, engine.check("오늘 안해").size)
        assertEquals(0, engine.check("보안해제").count { it.originalWord.startsWith("안") })
    }

    @Test
    fun replacementExpansion() {
        val m = Pattern.compile("(a)(b)?").matcher("xa")
        assertTrue(m.find())
        assertEquals("a-", RuleEngine.expandReplacement("$1-$2", m))
        assertEquals("[a]", RuleEngine.expandReplacement("[$0]", m))
        assertEquals("", RuleEngine.expandReplacement("$9", m))
        assertEquals("\$a", RuleEngine.expandReplacement("$$1", m))
        assertEquals("\$x", RuleEngine.expandReplacement("\$x", m))
        assertEquals("a0", RuleEngine.expandReplacement("$10", m))
    }

    @Test
    fun feedbackTemplateFallbackAndPlaceholders() {
        val templates = FeedbackTemplates("S {original}→{suggested}", "P {original}", "G {reason} {reason}")
        val c = Correction(0, 2, "몇개", "몇 개", "이유", wit = "")
        assertEquals("S 몇개→몇 개", Feedback.compose(listOf(c), FeedbackMode.SPICY_WIT, templates))
        assertEquals("P 몇개", Feedback.compose(listOf(c), FeedbackMode.POLICE, templates))
        assertEquals("G 이유 이유", Feedback.compose(listOf(c), FeedbackMode.GENTLE, templates))
        assertEquals("wit!", Feedback.compose(listOf(c.copy(wit = "wit!")), FeedbackMode.SPICY_WIT, templates))
        assertEquals(null, Feedback.compose(emptyList(), FeedbackMode.GENTLE, templates))
    }

    @Test
    fun applyCorrectionsProducesCorrectedText() {
        val text = "먹을때 연락할께"
        assertEquals("먹을 때 연락할게", Feedback.applyCorrections(text, engine.check(text)))
    }
}
