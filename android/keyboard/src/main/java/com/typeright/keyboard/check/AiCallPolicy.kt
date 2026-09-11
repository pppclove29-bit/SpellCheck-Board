package com.typeright.keyboard.check

import com.typeright.keyboard.account.AccountState

/** Decision for a sentence end (`.` `!` `?` newline) — docs/api-contract.md "클라이언트 트리거 정책". */
enum class AiDecision {
    /** Call `/v1/grammar-check`. */
    CALL,

    /** Skip: secure field, AI toggle off, offline, empty or unchanged text. */
    SKIP,

    /** Skip because the free quota is used up → show the "광고 보고 AI 훈수 3회 충전" chip. */
    QUOTA_EXHAUSTED,
}

object AiCallPolicy {
    /**
     * AI only at sentence end when: not secure, AI toggle on, network ok, (is_pro || remaining > 0) and the text
     * differs from the previous AI request.
     */
    fun decide(
        secure: Boolean,
        aiEnabled: Boolean,
        networkAvailable: Boolean,
        account: AccountState,
        text: String,
        lastAiText: String?,
    ): AiDecision = when {
        secure || !aiEnabled || !networkAvailable -> AiDecision.SKIP
        text.isBlank() || text == lastAiText -> AiDecision.SKIP
        !account.hasAiQuota -> AiDecision.QUOTA_EXHAUSTED
        else -> AiDecision.CALL
    }
}
