package com.typeright.keyboard.account

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RewardCreditPollerTest {
    private fun quota(bonus: Int) = Quota(isPro = false, limit = 5, used = 5, bonus = bonus, remaining = bonus)

    @Test
    fun stopsAsSoonAsBonusIncreases() = runTest {
        val sleeps = mutableListOf<Long>()
        val responses = ArrayDeque(listOf(quota(0), null, quota(3)))
        val credited = RewardCreditPoller.awaitBonusIncrease(
            initialBonus = 0,
            fetchQuota = { responses.removeFirst() },
            sleep = { sleeps += it },
        )
        assertEquals(3, credited!!.bonus)
        assertEquals(listOf(500L, 1_000L, 1_500L), sleeps)
    }

    @Test
    fun givesUpAfterAboutTenSeconds() = runTest {
        val sleeps = mutableListOf<Long>()
        var calls = 0
        val credited = RewardCreditPoller.awaitBonusIncrease(
            initialBonus = 3,
            fetchQuota = {
                calls++
                quota(3)
            },
            sleep = { sleeps += it },
        )
        assertNull(credited)
        assertEquals(RewardCreditPoller.DEFAULT_DELAYS_MS, sleeps)
        assertEquals(RewardCreditPoller.DEFAULT_DELAYS_MS.size, calls)
        assertEquals(10_000L, sleeps.sum())
    }
}
