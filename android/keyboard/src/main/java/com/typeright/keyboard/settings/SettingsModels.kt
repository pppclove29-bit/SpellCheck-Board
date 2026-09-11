package com.typeright.keyboard.settings

import com.typeright.keyboard.rules.FeedbackMode

enum class KoreanLayout(val label: String) {
    DUBEOLSIK("두벌식"),
    CHEONJIIN("천지인"),
}

enum class KeyboardLanguage { KOREAN, ENGLISH }

/** 단축어: typing [key] as the last token offers an expansion chip that replaces it with [expansion]. */
data class Shortcut(val key: String, val expansion: String)

data class TypeRightSettings(
    val aiEnabled: Boolean = true,
    val feedbackMode: FeedbackMode = FeedbackMode.DEFAULT,
    val shortcuts: List<Shortcut> = DEFAULT_SHORTCUTS,
    val koreanLayout: KoreanLayout = KoreanLayout.DUBEOLSIK,
    val lastLanguage: KeyboardLanguage = KeyboardLanguage.KOREAN,
) {
    companion object {
        val DEFAULT_SHORTCUTS = listOf(
            Shortcut("ㅈㅅ", "죄송합니다"),
            Shortcut("ㄱㅅ", "감사합니다"),
            Shortcut("ㅇㅋ", "알겠습니다"),
        )
    }
}
