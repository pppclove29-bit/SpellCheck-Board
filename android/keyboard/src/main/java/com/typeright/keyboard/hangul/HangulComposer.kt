package com.typeright.keyboard.hangul

/**
 * Output of one composer step.
 * The IME applies it as: `commitText(commit)` (if not empty, replacing the composing region),
 * then `setComposingText(composing)` (empty composing clears the region).
 */
data class ComposeResult(val commit: String, val composing: String)

/**
 * 두벌식 Hangul automaton (초성/중성/종성) with jamo-level backspace. Pure Kotlin, no Android deps.
 *
 * - Compound vowels (ㅘㅙㅚㅝㅞㅟㅢ) and 겹받침 (ㄳㄵㄶㄺㄻㄼㄽㄾㄿㅀㅄ).
 * - A vowel after a final consonant moves (the last part of) it to the next syllable: 갑+ㅏ → 가바, 값+ㅏ → 갑사.
 * - When a consonant cannot attach to the syllable (e.g. 가+ㄸ, 갈+ㅆ) the previous syllable is kept in the
 *   composing region until a vowel arrives, so backspace (and 천지인 consonant cycling) can go back into it.
 *
 * @param combineVowels 두벌식 vowel combining. 천지인 disables it and builds vowels itself.
 */
class HangulComposer(private val combineVowels: Boolean = true) : Composer {

    /** One syllable under construction, stored as the ordered jamo inputs for step-wise backspace. */
    private data class Syllable(
        val cho: Char? = null,
        val jungs: List<Char> = emptyList(),
        val jongs: List<Char> = emptyList(),
    ) {
        val isEmpty: Boolean get() = cho == null && jungs.isEmpty()

        val jung: Char?
            get() = when (jungs.size) {
                0 -> null
                1 -> jungs[0]
                else -> Hangul.combineVowels(jungs[0], jungs[1])
            }

        val jong: Char?
            get() = when (jongs.size) {
                0 -> null
                1 -> jongs[0]
                else -> Hangul.combineFinals(jongs[0], jongs[1])
            }

        val text: String
            get() {
                val v = jung
                return when {
                    cho != null && v != null -> Hangul.compose(cho, v, jong).toString()
                    cho != null -> cho.toString()
                    v != null -> v.toString()
                    else -> ""
                }
            }

        fun dropLast(): Syllable = when {
            jongs.isNotEmpty() -> copy(jongs = jongs.dropLast(1))
            jungs.isNotEmpty() -> copy(jungs = jungs.dropLast(1))
            else -> copy(cho = null)
        }
    }

    private var prev: Syllable? = null
    private var cur = Syllable()
    private val pendingCommit = StringBuilder()

    /** Text currently shown as composing (not yet committed). */
    override val composingText: String get() = (prev?.text ?: "") + cur.text

    override val isComposing: Boolean get() = prev != null || !cur.isEmpty

    /** Feeds one compatibility jamo. Any other character commits the composing text followed by that character. */
    fun input(ch: Char): ComposeResult {
        when {
            Hangul.isVowel(ch) -> inputVowel(ch)
            Hangul.isConsonant(ch) -> inputConsonant(ch)
            else -> {
                commitAll()
                pendingCommit.append(ch)
            }
        }
        return drain()
    }

    /** Removes the last jamo. Returns null when nothing is composing (the IME should delete before the cursor). */
    override fun backspace(): ComposeResult? {
        if (!isComposing) return null
        if (cur.isEmpty) {
            cur = prev!!
            prev = null
        }
        cur = cur.dropLast()
        if (cur.isEmpty && prev != null) {
            cur = prev!!
            prev = null
        }
        return drain()
    }

    /** Commits everything that is composing. */
    override fun finish(): ComposeResult {
        commitAll()
        return drain()
    }

    /** Drops composing state without committing (e.g. the editor already finished composing). */
    override fun reset() {
        prev = null
        cur = Syllable()
        pendingCommit.clear()
    }

    // --- 천지인 support -------------------------------------------------------------------------

    /** The vowel that may still be modified in place (current syllable ends with a vowel), else null. */
    internal fun modifiableVowel(): Char? =
        if (prev == null && cur.jongs.isEmpty()) cur.jung else null

    /** Replaces the current syllable's vowel as one unit (천지인 vowel building). */
    internal fun replaceLastVowel(vowel: Char): ComposeResult {
        check(modifiableVowel() != null) { "No vowel to replace" }
        require(Hangul.isVowel(vowel))
        cur = cur.copy(jungs = listOf(vowel))
        return drain()
    }

    // --- automaton ------------------------------------------------------------------------------

    private fun inputConsonant(c: Char) {
        val s = cur
        when {
            s.isEmpty -> cur = Syllable(cho = c)
            // Lone initial consonant (optionally after a kept syllable): consonants don't combine here.
            s.jungs.isEmpty() -> {
                commitAll()
                cur = Syllable(cho = c)
            }
            // Lone vowel (no initial): start a new syllable.
            s.cho == null -> {
                commitAll()
                cur = Syllable(cho = c)
            }
            s.jongs.isEmpty() && Hangul.canBeFinal(c) -> cur = s.copy(jongs = listOf(c))
            s.jongs.size == 1 && Hangul.combineFinals(s.jongs[0], c) != null -> cur = s.copy(jongs = s.jongs + c)
            else -> {
                // Cannot attach: keep the syllable composing until a vowel decides (see class doc).
                prev = s
                cur = Syllable(cho = c)
            }
        }
    }

    private fun inputVowel(v: Char) {
        val s = cur
        when {
            s.isEmpty -> cur = Syllable(jungs = listOf(v))
            s.jungs.isEmpty() -> {
                // Initial consonant + vowel. A kept previous syllable is now final.
                prev?.let { pendingCommit.append(it.text) }
                prev = null
                cur = s.copy(jungs = listOf(v))
            }
            s.jongs.isEmpty() -> {
                val combined = if (combineVowels && s.jungs.size == 1) Hangul.combineVowels(s.jungs[0], v) else null
                if (combined != null) {
                    cur = s.copy(jungs = s.jungs + v)
                } else {
                    pendingCommit.append(s.text)
                    cur = Syllable(jungs = listOf(v))
                }
            }
            else -> {
                // Final consonant moves to the next syllable: 갑+ㅏ → 가바, 값+ㅏ → 갑사.
                val moved = s.jongs.last()
                pendingCommit.append(s.copy(jongs = s.jongs.dropLast(1)).text)
                cur = Syllable(cho = moved, jungs = listOf(v))
            }
        }
    }

    private fun commitAll() {
        pendingCommit.append(composingText)
        prev = null
        cur = Syllable()
    }

    private fun drain(): ComposeResult {
        val commit = pendingCommit.toString()
        pendingCommit.clear()
        return ComposeResult(commit, composingText)
    }
}
