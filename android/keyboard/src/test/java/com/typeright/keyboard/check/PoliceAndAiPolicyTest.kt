package com.typeright.keyboard.check

import com.typeright.keyboard.account.AccountState
import com.typeright.keyboard.account.Quota
import com.typeright.keyboard.rules.Correction
import com.typeright.keyboard.rules.FeedbackMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoliceGateTest {
    private val snap = CheckSnapshot("오늘 몇일이야", absStart = 5)
    private val error = Correction(3, 2, "몇일", "며칠", "r")

    @Test
    fun blocksOnlyForProPoliceInNonSecureFieldsWithErrors() {
        val gate = PoliceGate()
        gate.enterSentence(snap.absStart)
        assertTrue(gate.shouldBlock(FeedbackMode.POLICE, isPro = true, secure = false, snap, listOf(error)))
        assertFalse(gate.shouldBlock(FeedbackMode.POLICE, isPro = false, secure = false, snap, listOf(error)))
        assertFalse(gate.shouldBlock(FeedbackMode.POLICE, isPro = true, secure = true, snap, listOf(error)))
        assertFalse(gate.shouldBlock(FeedbackMode.SPICY_WIT, isPro = true, secure = false, snap, listOf(error)))
        assertFalse(gate.shouldBlock(FeedbackMode.GENTLE, isPro = true, secure = false, snap, listOf(error)))
        assertFalse(gate.shouldBlock(FeedbackMode.POLICE, isPro = true, secure = false, snap, emptyList()))
        assertFalse(gate.shouldBlock(FeedbackMode.POLICE, isPro = true, secure = false, null, listOf(error)))
    }

    @Test
    fun ignoreUnblocksForCurrentSentenceOnly() {
        val gate = PoliceGate()
        gate.enterSentence(snap.absStart)
        gate.ignore(SpanKey.of(snap, error))
        assertFalse(gate.shouldBlock(FeedbackMode.POLICE, true, false, snap, listOf(error)))
        gate.enterSentence(snap.absStart) // same sentence: ignore list kept
        assertFalse(gate.shouldBlock(FeedbackMode.POLICE, true, false, snap, listOf(error)))
        gate.enterSentence(50) // new sentence: ignore list cleared
        val next = CheckSnapshot("오늘 몇일이야", absStart = 50 - 3)
        gate.enterSentence(next.absStart)
        assertTrue(gate.shouldBlock(FeedbackMode.POLICE, true, false, next, listOf(error)))
    }

    @Test
    fun newlyDetectedFiresOncePerError() {
        val gate = PoliceGate()
        gate.enterSentence(snap.absStart)
        assertEquals(listOf(error), gate.takeNewlyDetected(snap, listOf(error)))
        assertEquals(emptyList<Correction>(), gate.takeNewlyDetected(snap, listOf(error)))
        val other = Correction(0, 2, "오늘", "오늘날", "r")
        assertEquals(listOf(other), gate.takeNewlyDetected(snap, listOf(other, error)))
        gate.reset()
        assertEquals(listOf(error), gate.takeNewlyDetected(snap, listOf(error)))
    }
}

class AiCallPolicyTest {
    private val free = AccountState(isPro = false, quota = Quota(false, 5, 1, 0, 4))

    private fun decide(
        secure: Boolean = false,
        aiEnabled: Boolean = true,
        network: Boolean = true,
        account: AccountState = free,
        text: String = "오늘 몇일이야?",
        last: String? = null,
    ) = AiCallPolicy.decide(secure, aiEnabled, network, account, text, last)

    @Test
    fun callsWhenAllConditionsHold() = assertEquals(AiDecision.CALL, decide())

    @Test
    fun skipsSecureAiOffOfflineBlankOrUnchanged() {
        assertEquals(AiDecision.SKIP, decide(secure = true))
        assertEquals(AiDecision.SKIP, decide(aiEnabled = false))
        assertEquals(AiDecision.SKIP, decide(network = false))
        assertEquals(AiDecision.SKIP, decide(text = "  "))
        assertEquals(AiDecision.SKIP, decide(last = "오늘 몇일이야?"))
    }

    @Test
    fun quota() {
        val empty = AccountState(isPro = false, quota = Quota(false, 5, 5, 0, 0))
        assertEquals(AiDecision.QUOTA_EXHAUSTED, decide(account = empty))
        assertEquals(AiDecision.CALL, decide(account = empty.copy(isPro = true)))
        assertEquals(AiDecision.CALL, decide(account = AccountState())) // unknown quota: let the server decide
    }
}
