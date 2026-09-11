package com.typeright.keyboard.hangul

/**
 * Unicode Hangul tables. Input jamo are Hangul Compatibility Jamo (U+3131..U+3163),
 * which is what the keyboard keys emit and what the composer shows for lone jamo.
 */
object Hangul {
    /** 초성 (19), in Unicode syllable order. */
    const val CHOSEONG = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"

    /** 중성 (21), in Unicode syllable order. */
    const val JUNGSEONG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"

    /** 종성 (27 + "none" at index 0), in Unicode syllable order. */
    const val JONGSEONG = " ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ"

    private const val SYLLABLE_BASE = 0xAC00
    private const val JUNG_COUNT = 21
    private const val JONG_COUNT = 28

    /** 두벌식 compound vowels: first + second -> compound. */
    val VOWEL_COMBINATIONS: Map<Pair<Char, Char>, Char> = mapOf(
        ('ㅗ' to 'ㅏ') to 'ㅘ',
        ('ㅗ' to 'ㅐ') to 'ㅙ',
        ('ㅗ' to 'ㅣ') to 'ㅚ',
        ('ㅜ' to 'ㅓ') to 'ㅝ',
        ('ㅜ' to 'ㅔ') to 'ㅞ',
        ('ㅜ' to 'ㅣ') to 'ㅟ',
        ('ㅡ' to 'ㅣ') to 'ㅢ',
    )

    /** 겹받침: first + second -> compound final consonant. */
    val FINAL_COMBINATIONS: Map<Pair<Char, Char>, Char> = mapOf(
        ('ㄱ' to 'ㅅ') to 'ㄳ',
        ('ㄴ' to 'ㅈ') to 'ㄵ',
        ('ㄴ' to 'ㅎ') to 'ㄶ',
        ('ㄹ' to 'ㄱ') to 'ㄺ',
        ('ㄹ' to 'ㅁ') to 'ㄻ',
        ('ㄹ' to 'ㅂ') to 'ㄼ',
        ('ㄹ' to 'ㅅ') to 'ㄽ',
        ('ㄹ' to 'ㅌ') to 'ㄾ',
        ('ㄹ' to 'ㅍ') to 'ㄿ',
        ('ㄹ' to 'ㅎ') to 'ㅀ',
        ('ㅂ' to 'ㅅ') to 'ㅄ',
    )

    fun isConsonant(c: Char): Boolean = CHOSEONG.indexOf(c) >= 0

    fun isVowel(c: Char): Boolean = JUNGSEONG.indexOf(c) >= 0

    /** ㄸ ㅃ ㅉ cannot be a final consonant. */
    fun canBeFinal(c: Char): Boolean = c != ' ' && JONGSEONG.indexOf(c) > 0

    fun combineVowels(first: Char, second: Char): Char? = VOWEL_COMBINATIONS[first to second]

    fun combineFinals(first: Char, second: Char): Char? = FINAL_COMBINATIONS[first to second]

    /** Builds a precomposed syllable (가..힣). [jong] null means no final consonant. */
    fun compose(cho: Char, jung: Char, jong: Char?): Char {
        val c = CHOSEONG.indexOf(cho)
        val v = JUNGSEONG.indexOf(jung)
        val f = if (jong == null) 0 else JONGSEONG.indexOf(jong)
        require(c >= 0 && v >= 0 && f >= 0) { "Invalid jamo: $cho $jung $jong" }
        return (SYLLABLE_BASE + (c * JUNG_COUNT + v) * JONG_COUNT + f).toChar()
    }
}
