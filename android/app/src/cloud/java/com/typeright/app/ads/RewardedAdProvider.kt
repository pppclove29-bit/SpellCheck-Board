package com.typeright.app.ads

import android.app.Activity

sealed interface RewardedAdResult {
    /** The user earned the reward. The server credits +3 via the AdMob SSV callback; refresh `/v1/me` afterwards. */
    data object Rewarded : RewardedAdResult
    data object Dismissed : RewardedAdResult
    data object NotAvailable : RewardedAdResult
    data class Failed(val message: String) : RewardedAdResult
}

/**
 * Rewarded ads, host app only (never inside the IME). The AdMob implementation is a later milestone.
 *
 * IMPORTANT for the real implementation: set `ServerSideVerificationOptions.userId` to [userId] — the Supabase user
 * id — so the backend's `/v1/ads/admob-ssv` callback credits the right user (the client never credits itself).
 */
interface RewardedAdProvider {
    val isAvailable: Boolean

    suspend fun showRewardedAd(activity: Activity, userId: String): RewardedAdResult
}

/** Placeholder until AdMob is integrated. */
class StubRewardedAdProvider : RewardedAdProvider {
    override val isAvailable: Boolean = false

    override suspend fun showRewardedAd(activity: Activity, userId: String): RewardedAdResult = RewardedAdResult.NotAvailable
}
