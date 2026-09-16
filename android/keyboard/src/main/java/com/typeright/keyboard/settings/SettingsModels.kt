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
    /** AI features are OFF by default; turning them on requires the data-transfer consent (Play prominent disclosure). */
    val aiEnabled: Boolean = false,
    /** When the user agreed to the AI data-transfer disclosure, or null if never. */
    val aiConsentAtMillis: Long? = null,
    val feedbackMode: FeedbackMode = FeedbackMode.DEFAULT,
    /** Custom (PRO) shortcuts only; the read-only built-ins are [ShortcutRules.BUILT_IN]. */
    val shortcuts: List<Shortcut> = emptyList(),
    val koreanLayout: KoreanLayout = KoreanLayout.DUBEOLSIK,
    val lastLanguage: KeyboardLanguage = KeyboardLanguage.KOREAN,
    /** 앱별 자동 모드: built-in app → mode map (KakaoTalk = spicy, Slack/mail = gentle, …). */
    val appAutoMode: Boolean = true,
    /** Modes the user picked for specific apps via the keyboard's mode chip (package name → mode). */
    val appModeOverrides: Map<String, FeedbackMode> = emptyMap(),
    /** Haptic pulse on each key press. On by default (what the keyboard did before the toggle existed). */
    val keyVibration: Boolean = true,
    /** Click sound on each key press. Off by default; the system's own key-click setting still wins. */
    val keySound: Boolean = false,
    /** 최근 tab of the emoji panel, most recent first. */
    val recentEmoji: List<String> = emptyList(),
) {
    /** Feedback mode for the app being typed in ([packageName] from EditorInfo / the PROCESS_TEXT caller). */
    fun modeFor(packageName: String?): FeedbackMode =
        FeedbackModeResolver.resolve(packageName, appModeOverrides, appAutoMode, feedbackMode)

    val aiConsented: Boolean get() = aiConsentAtMillis != null

    /** AI network calls are allowed only when the toggle is on AND consent was recorded. */
    val aiActive: Boolean get() = aiEnabled && aiConsented

    /** Built-ins (everyone) + custom (PRO) — what the keyboard matches against. */
    val allShortcuts: List<Shortcut> get() = ShortcutRules.BUILT_IN + shortcuts
}
