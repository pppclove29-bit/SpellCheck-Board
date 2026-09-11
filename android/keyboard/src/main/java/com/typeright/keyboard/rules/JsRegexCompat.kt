package com.typeright.keyboard.rules

/**
 * Normalises a rule pattern (written for JS / Java / ICU common syntax) so that it behaves identically on
 * the JVM (unit tests, OpenJDK regex) and on Android (ICU-backed java.util.regex) and matches JS semantics.
 *
 * The shorthand classes differ between engines: ICU's `\s`, `\d`, `\w` are Unicode-aware while OpenJDK's are
 * ASCII-only, and JS `\s` is Unicode whitespace but `\d`/`\w` are ASCII. They are rewritten to explicit
 * JS-equivalent classes. Inside a character class, `[` and `&&` are escaped because Java/ICU treat them as
 * nested-class / intersection operators while JS treats them as literals.
 */
object JsRegexCompat {
    /** ECMAScript WhiteSpace + LineTerminator. */
    private const val JS_SPACE = "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
    private const val JS_DIGIT = "0-9"
    private const val JS_WORD = "A-Za-z0-9_"

    fun toJavaPattern(pattern: String): String {
        val out = StringBuilder(pattern.length + 16)
        var inClass = false
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' && i + 1 < pattern.length -> {
                    val next = pattern[i + 1]
                    val body = when (next.lowercaseChar()) {
                        's' -> JS_SPACE
                        'd' -> JS_DIGIT
                        'w' -> JS_WORD
                        else -> null
                    }
                    if (body == null) {
                        out.append(c).append(next)
                    } else {
                        val negated = next.isUpperCase()
                        when {
                            !inClass -> out.append(if (negated) "[^" else "[").append(body).append(']')
                            // Java/ICU support nested classes as a union, which expresses a negated shorthand in a class.
                            negated -> out.append("[^").append(body).append(']')
                            else -> out.append(body)
                        }
                    }
                    i += 2
                    continue
                }
                !inClass && c == '[' -> {
                    inClass = true
                    out.append(c)
                    // A leading '^' belongs to the class syntax.
                    if (i + 1 < pattern.length && pattern[i + 1] == '^') {
                        out.append('^')
                        i++
                    }
                }
                inClass && c == ']' -> {
                    inClass = false
                    out.append(c)
                }
                inClass && c == '[' -> out.append("\\[")
                inClass && c == '&' && i + 1 < pattern.length && pattern[i + 1] == '&' -> {
                    out.append("&\\&")
                    i++
                }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }
}
