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
) {
    /** Unknown quota (never fetched) is treated as available; the server enforces the real limit. */
    val hasAiQuota: Boolean get() = isPro || quota == null || quota.remaining > 0
}
