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
    /**
     * AI only at sentence end when: not secure, AI toggle on AND consent recorded, signed in (or dev auth),
     * network ok, (is_pro || remaining > 0) and the text differs from the previous AI request.
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
        !account.hasAiQuota -> AiDecision.QUOTA_EXHAUSTED
        else -> AiDecision.CALL
    }
}
