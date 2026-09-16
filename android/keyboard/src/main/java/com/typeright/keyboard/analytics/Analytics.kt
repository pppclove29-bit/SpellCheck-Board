package com.typeright.keyboard.analytics

/**
 * Product metrics. Defined here (no Firebase dependency) so the IME module can report without knowing the sink;
 * the host app supplies the real one.
 *
 * **Never log what the user typed.** Not the text, not a correction's words, not the app they are typing in.
 * The sink is a third party, and this is a keyboard — the whole product rests on that line not being crossed.
 * Everything here is a count, an enum or a duration bucket. [AnalyticsEventTest] enforces the naming rules.
 */
interface Analytics {
    fun log(event: AnalyticsEvent)

    /** PRO / signed-in state, so retention can be split by segment without joining on a user id. */
    fun setUserProperty(property: UserProperty, value: String)

    object None : Analytics {
        override fun log(event: AnalyticsEvent) = Unit
        override fun setUserProperty(property: UserProperty, value: String) = Unit
    }
}

enum class UserProperty(val key: String) {
    PLAN("plan"), // free | pro
    SIGNED_IN("signed_in"), // true | false
    FEEDBACK_MODE("feedback_mode"), // spicy_wit | police | gentle
}

/**
 * One analytics event. [name] and every parameter key follow Firebase's rules (≤ 40 chars, letters/digits/underscore,
 * starting with a letter, no `firebase_`/`google_`/`ga_` prefix).
 */
data class AnalyticsEvent(val name: String, val params: Map<String, Any> = emptyMap())

/**
 * The event catalogue. Adding an event means adding it here, so the full list of what leaves the device is one
 * file that a person can read top to bottom.
 */
object Events {

    // --- the two metrics that decide continue / pivot (11.1) -----------------------------------

    /** The keyboard was shown. The unit of "is this actually being used as their keyboard?". */
    fun keyboardShown(appCategory: AppCategory) =
        AnalyticsEvent("keyboard_shown", mapOf("app_category" to appCategory.key))

    /** Host app saw TypeRight enabled in system settings / selected as the active keyboard. */
    fun imeStatus(enabled: Boolean, isDefault: Boolean) =
        AnalyticsEvent("ime_status", mapOf("enabled" to enabled.toString(), "is_default" to isDefault.toString()))

    // --- core loop ------------------------------------------------------------------------------

    /** Rule (on-device) corrections were offered. [count] only — never the words. */
    fun rulesFound(count: Int) = AnalyticsEvent("rules_found", mapOf("count" to count))

    /** An AI check finished. [status] is the contract's `ai_status`. */
    fun aiChecked(status: String) = AnalyticsEvent("ai_checked", mapOf("ai_status" to status))

    /** The user accepted a correction — the closest thing to "this product worked". */
    fun correctionApplied(source: String) = AnalyticsEvent("correction_applied", mapOf("source" to source))

    /** 맞춤법 경찰 '무시': the user rejected a correction. High values mean false positives. */
    fun correctionIgnored() = AnalyticsEvent("correction_ignored")

    fun feedbackShown(mode: String) = AnalyticsEvent("feedback_shown", mapOf("mode" to mode))

    fun modeChanged(mode: String, perApp: Boolean) =
        AnalyticsEvent("mode_changed", mapOf("mode" to mode, "per_app" to perApp.toString()))

    /** Daily AI quota hit zero: the moment that drives ads and PRO. */
    fun quotaExhausted(isPro: Boolean) = AnalyticsEvent("quota_exhausted", mapOf("is_pro" to isPro.toString()))

    // --- growth features (11.2) -----------------------------------------------------------------

    /** 텍스트 선택 메뉴 [맛춤뻡 검사] — entry without switching keyboards. */
    fun processTextOpened() = AnalyticsEvent("process_text_opened")

    fun shareCardCreated(mode: String) = AnalyticsEvent("share_card_created", mapOf("mode" to mode))

    /** The card left the app (share sheet or gallery): the viral step. */
    fun shareCardShared(target: String) = AnalyticsEvent("share_card_shared", mapOf("target" to target))

    // --- money ----------------------------------------------------------------------------------

    fun rewardAdWatched(credited: Boolean) = AnalyticsEvent("reward_ad_watched", mapOf("credited" to credited.toString()))

    fun purchaseStarted(productId: String) = AnalyticsEvent("purchase_started", mapOf("product_id" to productId))

    /** [result] is the server's verify outcome (activated / not_found / …), so failed funnels are visible. */
    fun purchaseVerified(productId: String, result: String) =
        AnalyticsEvent("purchase_verified", mapOf("product_id" to productId, "result" to result))

    fun signedIn() = AnalyticsEvent("signed_in")
}

/**
 * Coarse bucket for the app being typed in.
 *
 * The package name is deliberately **not** logged: a per-user list of which apps they type in is a behavioural
 * profile we have no business collecting. The bucket is enough to answer "does 앱별 모드 matter?".
 */
enum class AppCategory(val key: String) {
    MESSENGER("messenger"),
    SOCIAL("social"),
    WORK("work"),
    BROWSER("browser"),
    OTHER("other"),
}

/** Maps a package name onto an [AppCategory]. The package itself never leaves the device. */
object AppCategories {

    private val KNOWN: Map<String, AppCategory> = mapOf(
        "com.kakao.talk" to AppCategory.MESSENGER,
        "com.facebook.orca" to AppCategory.MESSENGER,
        "jp.naver.line.android" to AppCategory.MESSENGER,
        "com.discord" to AppCategory.MESSENGER,
        "org.telegram.messenger" to AppCategory.MESSENGER,
        "com.whatsapp" to AppCategory.MESSENGER,
        "com.instagram.android" to AppCategory.SOCIAL,
        "com.twitter.android" to AppCategory.SOCIAL,
        "com.facebook.katana" to AppCategory.SOCIAL,
        "com.ss.android.ugc.trill" to AppCategory.SOCIAL,
        "com.zhiliaoapp.musically" to AppCategory.SOCIAL,
        "com.Slack" to AppCategory.WORK,
        "com.google.android.gm" to AppCategory.WORK,
        "com.microsoft.office.outlook" to AppCategory.WORK,
        "com.microsoft.teams" to AppCategory.WORK,
        "notion.id" to AppCategory.WORK,
        "com.nhn.android.mail" to AppCategory.WORK,
        "com.samsung.android.email.provider" to AppCategory.WORK,
        "com.tosslab.jandi.app" to AppCategory.WORK,
        "com.android.chrome" to AppCategory.BROWSER,
        "com.sec.android.app.sbrowser" to AppCategory.BROWSER,
        "org.mozilla.firefox" to AppCategory.BROWSER,
        "com.microsoft.emmx" to AppCategory.BROWSER,
        "com.nhn.android.search" to AppCategory.BROWSER,
    )

    fun of(packageName: String?): AppCategory = KNOWN[packageName] ?: AppCategory.OTHER
}
