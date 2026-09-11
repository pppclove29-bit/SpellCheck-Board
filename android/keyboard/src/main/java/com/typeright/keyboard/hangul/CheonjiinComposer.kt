package com.typeright.keyboard.hangul

/** 천지인 consonant keys and the consonants each key cycles through on repeated taps. */
enum class CheonjiinConsonantKey(val cycle: List<Char>, val label: String) {
    GIYEOK(listOf('ㄱ', 'ㅋ', 'ㄲ'), "ㄱㅋ"),
    NIEUN(listOf('ㄴ', 'ㄹ'), "ㄴㄹ"),
    DIGEUT(listOf('ㄷ', 'ㅌ', 'ㄸ'), "ㄷㅌ"),
    BIEUP(listOf('ㅂ', 'ㅍ', 'ㅃ'), "ㅂㅍ"),
    SIOT(listOf('ㅅ', 'ㅎ', 'ㅆ'), "ㅅㅎ"),
    JIEUT(listOf('ㅈ', 'ㅊ', 'ㅉ'), "ㅈㅊ"),
    IEUNG(listOf('ㅇ', 'ㅁ'), "ㅇㅁ"),
}

/** 천지인 vowel strokes: ㅣ (사람), ㆍ (하늘), ㅡ (땅). */
enum class CheonjiinStroke(val symbol: Char) {
    I('ㅣ'),
    DOT('ㆍ'),
    EU('ㅡ'),
}

/**
 * 천지인 input on top of [HangulComposer].
 *
 * Vowels are built from strokes (ㅏ=ㅣㆍ, ㅓ=ㆍㅣ, ㅗ=ㆍㅡ, ㅜ=ㅡㆍ, ㅑ=ㅣㆍㆍ, ㅕ=ㆍㆍㅣ, ㅛ=ㆍㆍㅡ, ㅠ=ㅡㆍㆍ,
 * ㅐ=ㅏㅣ, ㅔ=ㅓㅣ, ㅒ=ㅑㅣ, ㅖ=ㅕㅣ, ㅘ=ㅗㅏ, ㅙ=ㅘㅣ, ㅚ=ㅗㅣ, ㅝ=ㅜㅓ, ㅞ=ㅝㅣ, ㅟ=ㅜㅣ, ㅢ=ㅡㅣ).
 * A lone ㆍ/ㆍㆍ is shown pending after the composing text until the next stroke resolves it.
 * Taps on the same consonant key within [CYCLE_WINDOW_MS] cycle its consonants (ㄱ→ㅋ→ㄲ→ㄱ); after an idle pause
 * the same key starts a new consonant (ㄱ, pause, ㄱ = ㄱㄱ). The clock is injectable for tests.
 */
class CheonjiinComposer(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val cycleWindowMillis: Long = CYCLE_WINDOW_MS,
) : Composer {
    private val hangul = HangulComposer(combineVowels = false)
    private var lastConsonantAt = 0L

    /** 0 = none, 1 = ㆍ, 2 = ㆍㆍ. */
    private var pendingDots = 0
    private var lastConsonantKey: CheonjiinConsonantKey? = null
    private var cycleIndex = 0

    override val composingText: String
        get() = hangul.composingText + DOT.repeat(pendingDots)

    override val isComposing: Boolean get() = hangul.isComposing || pendingDots > 0

    fun inputConsonant(key: CheonjiinConsonantKey): ComposeResult {
        pendingDots = 0 // a dangling ㆍ before a consonant is meaningless; drop it
        val now = nowMillis()
        // Same key again within the window cycles (ㄱ→ㅋ→ㄲ); after an idle pause it starts a new consonant.
        val cycling = key == lastConsonantKey && now - lastConsonantAt <= cycleWindowMillis
        lastConsonantAt = now
        val result = if (cycling) {
            cycleIndex = (cycleIndex + 1) % key.cycle.size
            hangul.backspace() // removes the consonant produced by the previous tap
            hangul.input(key.cycle[cycleIndex])
        } else {
            lastConsonantKey = key
            cycleIndex = 0
            hangul.input(key.cycle[0])
        }
        return withDots(result)
    }

    fun inputStroke(stroke: CheonjiinStroke): ComposeResult {
        lastConsonantKey = null

        if (pendingDots > 0) {
            val vowel = when (stroke) {
                CheonjiinStroke.DOT -> {
                    pendingDots = if (pendingDots == 1) 2 else 1
                    return ComposeResult("", composingText)
                }
                CheonjiinStroke.I -> if (pendingDots == 1) 'ㅓ' else 'ㅕ'
                CheonjiinStroke.EU -> if (pendingDots == 1) 'ㅗ' else 'ㅛ'
            }
            pendingDots = 0
            return withDots(hangul.input(vowel))
        }

        val current = hangul.modifiableVowel()
        if (current != null) {
            val combined = COMBINATIONS[current to stroke.symbol]
            if (combined != null) return withDots(hangul.replaceLastVowel(combined))
        }

        return when (stroke) {
            CheonjiinStroke.DOT -> {
                pendingDots = 1
                ComposeResult("", composingText)
            }
            else -> withDots(hangul.input(stroke.symbol))
        }
    }

    /** Stops consonant cycling so the next tap on the same key inserts a new consonant. */
    fun breakCycle() {
        lastConsonantKey = null
    }

    override fun backspace(): ComposeResult? {
        lastConsonantKey = null
        if (pendingDots > 0) {
            pendingDots--
            return ComposeResult("", composingText)
        }
        return hangul.backspace()
    }

    override fun finish(): ComposeResult {
        lastConsonantKey = null
        pendingDots = 0
        return hangul.finish()
    }

    override fun reset() {
        lastConsonantKey = null
        pendingDots = 0
        hangul.reset()
    }

    private fun withDots(result: ComposeResult): ComposeResult =
        if (pendingDots == 0) result else result.copy(composing = composingText)

    companion object {
        private const val DOT = "ㆍ"

        /** Repeated taps of the same consonant key within this window cycle; slower taps start a new consonant. */
        const val CYCLE_WINDOW_MS = 300L

        /** (current vowel, stroke) -> new vowel. */
        val COMBINATIONS: Map<Pair<Char, Char>, Char> = mapOf(
            ('ㅣ' to 'ㆍ') to 'ㅏ',
            ('ㅏ' to 'ㆍ') to 'ㅑ',
            ('ㅏ' to 'ㅣ') to 'ㅐ',
            ('ㅑ' to 'ㅣ') to 'ㅒ',
            ('ㅓ' to 'ㅣ') to 'ㅔ',
            ('ㅕ' to 'ㅣ') to 'ㅖ',
            ('ㅡ' to 'ㆍ') to 'ㅜ',
            ('ㅜ' to 'ㆍ') to 'ㅠ',
            ('ㅡ' to 'ㅣ') to 'ㅢ',
            ('ㅜ' to 'ㅣ') to 'ㅟ',
            ('ㅠ' to 'ㅣ') to 'ㅝ',
            ('ㅝ' to 'ㅣ') to 'ㅞ',
            ('ㅗ' to 'ㅣ') to 'ㅚ',
            ('ㅚ' to 'ㆍ') to 'ㅘ',
            ('ㅘ' to 'ㅣ') to 'ㅙ',
        )
    }
}
