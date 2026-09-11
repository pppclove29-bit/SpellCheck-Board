package com.typeright.keyboard.settings

import com.typeright.keyboard.rules.FeedbackMode

/**
 * 앱별 피드백 모드 (docs/api-contract.md "앱별 피드백 모드"):
 * user per-app override > built-in map (only when "앱별 자동 모드" is on) > global default mode.
 * Built-ins never pick police — that stays an explicit user choice.
 */
object FeedbackModeResolver {
    data class KnownApp(val label: String, val mode: FeedbackMode)

    val BUILT_IN: Map<String, KnownApp> = mapOf(
        // Messengers / SNS → 매운맛 (fun, shareable)
        "com.kakao.talk" to KnownApp("카카오톡", FeedbackMode.SPICY_WIT),
        "com.instagram.android" to KnownApp("인스타그램", FeedbackMode.SPICY_WIT),
        "com.facebook.orca" to KnownApp("메신저", FeedbackMode.SPICY_WIT),
        "com.discord" to KnownApp("디스코드", FeedbackMode.SPICY_WIT),
        "com.twitter.android" to KnownApp("X", FeedbackMode.SPICY_WIT),
        "jp.naver.line.android" to KnownApp("라인", FeedbackMode.SPICY_WIT),
        // Work → 상냥한 선생님 (mistake prevention, the paying audience)
        "com.Slack" to KnownApp("슬랙", FeedbackMode.GENTLE),
        "com.google.android.gm" to KnownApp("Gmail", FeedbackMode.GENTLE),
        "com.microsoft.office.outlook" to KnownApp("Outlook", FeedbackMode.GENTLE),
        "com.microsoft.teams" to KnownApp("Teams", FeedbackMode.GENTLE),
        "notion.id" to KnownApp("노션", FeedbackMode.GENTLE),
        "com.nhn.android.mail" to KnownApp("네이버 메일", FeedbackMode.GENTLE),
        "com.samsung.android.email.provider" to KnownApp("삼성 이메일", FeedbackMode.GENTLE),
        "com.tosslab.jandi.app" to KnownApp("잔디", FeedbackMode.GENTLE),
    )

    /** Mode chip cycle: 🌶️ → 🍎 → 🚨 → 🌶️. */
    private val CYCLE = listOf(FeedbackMode.SPICY_WIT, FeedbackMode.GENTLE, FeedbackMode.POLICE)

    fun resolve(
        packageName: String?,
        overrides: Map<String, FeedbackMode>,
        autoEnabled: Boolean,
        globalDefault: FeedbackMode,
    ): FeedbackMode {
        if (packageName.isNullOrEmpty()) return globalDefault
        overrides[packageName]?.let { return it }
        if (autoEnabled) BUILT_IN[packageName]?.let { return it.mode }
        return globalDefault
    }

    /** Next mode for the keyboard's mode chip; police is skipped when not allowed (signed out). */
    fun next(current: FeedbackMode, allowPolice: Boolean): FeedbackMode {
        var i = CYCLE.indexOf(current)
        repeat(CYCLE.size) {
            i = (i + 1) % CYCLE.size
            val candidate = CYCLE[i]
            if (candidate != FeedbackMode.POLICE || allowPolice) return candidate
        }
        return current
    }

    fun appLabel(packageName: String): String = BUILT_IN[packageName]?.label ?: packageName

    fun emoji(mode: FeedbackMode): String = when (mode) {
        FeedbackMode.SPICY_WIT -> "🌶️"
        FeedbackMode.POLICE -> "🚨"
        FeedbackMode.GENTLE -> "🍎"
    }
}

/** Per-app overrides stored as "package\tmode" lines in one preference. */
object AppModeCodec {
    fun encode(map: Map<String, FeedbackMode>): String =
        map.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}\t${it.value.apiValue}" }

    fun decode(value: String?): Map<String, FeedbackMode> =
        value.orEmpty().lineSequence().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@mapNotNull null
            val mode = FeedbackMode.fromApi(line.substring(tab + 1)) ?: return@mapNotNull null
            line.substring(0, tab) to mode
        }.toMap()
}
