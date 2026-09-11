package com.typeright.keyboard.account

/** `quota` object of `/v1/grammar-check` and `/v1/me`. */
data class Quota(
    val isPro: Boolean,
    val limit: Int,
    val used: Int,
    val bonus: Int,
    val remaining: Int,
)

/** Cached `/v1/me` (+ latest quota from grammar-check responses), shared by IME and host app. */
data class AccountState(
    val userId: String? = null,
    val isPro: Boolean = false,
    val quota: Quota? = null,
    val fetchedAtMillis: Long = 0L,
    /** Monthly AI budget paused server-side (`ai_status = paused` / `/v1/me.ai_paused`): on-device checks only. */
    val aiPaused: Boolean = false,
) {
    /** Unknown quota (never fetched) is treated as available; the server enforces the real limit. */
    val hasAiQuota: Boolean get() = isPro || quota == null || quota.remaining > 0

    /**
     * Whether `/v1/me` should be re-fetched now (called at keyboard start). Normally every [STALE_AFTER_MS];
     * while AI is paused at most once per [AI_PAUSED_RECHECK_MS].
     */
    fun isRefreshDue(nowMillis: Long): Boolean =
        nowMillis - fetchedAtMillis >= if (aiPaused) AI_PAUSED_RECHECK_MS else STALE_AFTER_MS

    companion object {
        const val STALE_AFTER_MS = 5 * 60_000L
        const val AI_PAUSED_RECHECK_MS = 60 * 60_000L
    }
}
