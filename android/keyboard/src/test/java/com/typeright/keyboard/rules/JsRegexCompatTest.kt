package com.typeright.keyboard.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

class JsRegexCompatTest {
    private fun matches(jsPattern: String, input: String) =
        Pattern.compile(JsRegexCompat.toJavaPattern(jsPattern)).matcher(input).matches()

    /** "a" + the given code point + "b" (built from code points to keep invisible characters out of the source). */
    private fun around(codePoint: Int) = "a" + codePoint.toChar() + "b"

    @Test
    fun whitespaceFollowsJsSemantics() {
        assertTrue(matches("a\\sb", "a b"))
        assertTrue(matches("a\\sb", around(0x00A0))) // NBSP
        assertTrue(matches("a\\sb", around(0x3000))) // ideographic space
        assertTrue(matches("a\\sb", around(0xFEFF))) // BOM
        assertTrue(matches("a\\sb", around(0x2009))) // thin space
        assertFalse(matches("a\\sb", around(0x0085))) // NEL is not JS whitespace
        assertTrue(matches("a\\Sb", "a가b"))
        assertFalse(matches("a\\Sb", "a b"))
    }

    @Test
    fun jsWhitespaceHelperMatchesRegexTranslation() {
        for (cp in 0..0xFFFF) {
            val c = cp.toChar()
            if (Character.isSurrogate(c)) continue
            assertEquals("U+%04X".format(cp), Feedback.isJsWhitespace(c), matches("\\s", c.toString()))
        }
    }

    @Test
    fun digitsAndWordCharsAreAscii() {
        assertTrue(matches("\\d", "7"))
        assertFalse(matches("\\d", 0x0667.toChar().toString())) // Arabic-Indic digit seven
        assertTrue(matches("\\w", "_"))
        assertFalse(matches("\\w", "가"))
    }

    @Test
    fun shorthandInsideCharacterClass() {
        assertTrue(matches("[\\s가]", " "))
        assertTrue(matches("[\\s가]", "가"))
        assertTrue(matches("[^\\s]", "a"))
        assertFalse(matches("[^\\s]", " "))
        assertTrue(matches("[\\S]", "a"))
        assertFalse(matches("[\\S]", " "))
    }

    @Test
    fun jsLiteralsInsideClassStayLiteral() {
        assertTrue(matches("[[]", "["))
        assertTrue(matches("[a&&b]", "&"))
    }

    @Test
    fun lookaroundsAndGroupsUntouched() {
        val p = "(?<![가-힣])(?:내|너)꺼(?=가)"
        assertEquals(p, JsRegexCompat.toJavaPattern(p))
    }
}
