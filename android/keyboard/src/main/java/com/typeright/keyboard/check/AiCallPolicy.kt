package com.typeright.keyboard.check

import com.typeright.keyboard.account.AccountState

/** Decision for a sentence end (`.` `!` `?` newline) — docs/api-contract.md "클라이언트 트리거 정책". */
enum class AiDecision {
    /** Call `/v1/grammar-check`. */
    CALL,

    /** Skip: secure field, AI off / no consent, signed out, offline, empty or unchanged text. */
    SKIP,

    /** Skip because the free quota is used up (the ⚡충전 button is emphasized). */
    QUOTA_EXHAUSTED,
}

object AiCallPolicy {
    const val MAX_AI_CHARS = 150
    const val MIN_HANGUL_SYLLABLES = 2

    /**
     * Same eligibility rule as the server (which otherwise answers `ai_status = skipped`): at most 150 characters
     * and at least 2 Hangul syllables. Counted in code points because the server's Python `len()` does.
     */
    fun isAiEligible(text: String): Boolean =
        text.codePointCount(0, text.length) <= MAX_AI_CHARS && text.count { it in '가'..'힣' } >= MIN_HANGUL_SYLLABLES

    /**
     * AI only at sentence end when: not secure, AI toggle on AND consent recorded, signed in (or dev auth),
     * network ok, the text is AI-eligible, (is_pro || remaining > 0) and it differs from the previous AI request.
     */
    fun decide(
        secure: Boolean,
        aiEnabled: Boolean,
        aiConsented: Boolean,
        signedIn: Boolean,
        networkAvailable: Boolean,
        account: AccountState,
        text: String,
        lastAiText: String?,
    ): AiDecision = when {
        secure || !aiEnabled || !aiConsented || !signedIn || !networkAvailable -> AiDecision.SKIP
        text.isBlank() || text == lastAiText -> AiDecision.SKIP
        !isAiEligible(text) -> AiDecision.SKIP
        // Monthly AI budget paused server-side: on-device only until /v1/me reports ai_paused == false.
        account.aiPaused -> AiDecision.SKIP
        !account.hasAiQuota -> AiDecision.QUOTA_EXHAUSTED
        else -> AiDecision.CALL
    }
}
