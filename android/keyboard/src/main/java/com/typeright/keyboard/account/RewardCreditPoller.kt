package com.typeright.keyboard.account

import kotlinx.coroutines.delay

/**
 * After a rewarded ad, the +3 credit arrives asynchronously (AdMob SSV callback → server). Poll `/v1/me` with
 * backoff for up to ~10 s until the bonus increases.
 */
object RewardCreditPoller {
    /** 0.5 + 1 + 1.5 + 2 + 2.5 + 2.5 = 10 s in total. */
    val DEFAULT_DELAYS_MS: List<Long> = listOf(500L, 1_000L, 1_500L, 2_000L, 2_500L, 2_500L)

    /**
     * @param initialBonus `quota.bonus` before the ad was shown
     * @param fetchQuota fetches the latest quota (null on failure — polling continues)
     * @return the credited quota, or null if the credit did not show up in time
     */
    suspend fun awaitBonusIncrease(
        initialBonus: Int,
        fetchQuota: suspend () -> Quota?,
        delaysMs: List<Long> = DEFAULT_DELAYS_MS,
        sleep: suspend (Long) -> Unit = { delay(it) },
    ): Quota? {
        for (d in delaysMs) {
            sleep(d)
            val q = fetchQuota()
            if (q != null && q.bonus > initialBonus) return q
        }
        return null
    }
}
