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
    private val snap = CheckSnapshot("오늘 몇일이야 할께", absStart = 5)
    private val error = Correction(3, 2, "몇일", "며칠", "r")
    private val error2 = Correction(8, 2, "할께", "할게", "r")

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
    fun ignoreTurnsPoliceOffForTheWholeSentence() {
        val gate = PoliceGate()
        gate.enterSentence(snap.absStart)
        gate.ignoreSentence(snap.absStart)
        assertTrue(gate.isSentenceIgnored(snap.absStart))
        // Every correction in the sentence is unblocked, and no siren fires for them.
        assertFalse(gate.shouldBlock(FeedbackMode.POLICE, true, false, snap, listOf(error, error2)))
        assertEquals(emptyList<Correction>(), gate.takeNewlyDetected(snap, listOf(error, error2)))
        // Re-entering the same sentence keeps it ignored.
        gate.enterSentence(snap.absStart)
        assertFalse(gate.shouldBlock(FeedbackMode.POLICE, true, false, snap, listOf(error)))
    }

    @Test
    fun ignoreResetsWhenANewSentenceStartsOrTheStartMoves() {
        val gate = PoliceGate()
        gate.ignoreSentence(snap.absStart)
        val next = CheckSnapshot("다음 문장 몇일", absStart = 30)
        gate.enterSentence(next.absStart)
        assertTrue(gate.shouldBlock(FeedbackMode.POLICE, true, false, next, listOf(error)))

        gate.ignoreSentence(next.absStart)
        val moved = next.copy(absStart = 31) // text inserted before the sentence
        gate.enterSentence(moved.absStart)
        assertFalse(gate.isSentenceIgnored(moved.absStart))
        assertTrue(gate.shouldBlock(FeedbackMode.POLICE, true, false, moved, listOf(error)))
        gate.enterSentence(next.absStart) // moving back does not restore the old ignore
        assertFalse(gate.isSentenceIgnored(next.absStart))
    }

    @Test
    fun newlyDetectedFiresOncePerError() {
        val gate = PoliceGate()
        gate.enterSentence(snap.absStart)
        assertEquals(listOf(error), gate.takeNewlyDetected(snap, listOf(error)))
        assertEquals(emptyList<Correction>(), gate.takeNewlyDetected(snap, listOf(error)))
        assertEquals(listOf(error2), gate.takeNewlyDetected(snap, listOf(error, error2)))
        gate.reset()
        assertEquals(listOf(error), gate.takeNewlyDetected(snap, listOf(error)))
    }
}

class AiCallPolicyTest {
    private val free = AccountState(isPro = false, quota = Quota(false, 5, 1, 0, 4))

    private fun decide(
        secure: Boolean = false,
        aiEnabled: Boolean = true,
        consented: Boolean = true,
        signedIn: Boolean = true,
        network: Boolean = true,
        account: AccountState = free,
        text: String = "오늘 몇일이야?",
        last: String? = null,
    ) = AiCallPolicy.decide(secure, aiEnabled, consented, signedIn, network, account, text, last)

    @Test
    fun callsWhenAllConditionsHold() = assertEquals(AiDecision.CALL, decide())

    @Test
    fun skipsWithoutConsentOrSignIn() {
        assertEquals(AiDecision.SKIP, decide(consented = false))
        assertEquals(AiDecision.SKIP, decide(aiEnabled = false))
        assertEquals(AiDecision.SKIP, decide(signedIn = false))
    }

    @Test
    fun skipsSecureOfflineBlankOrUnchanged() {
        assertEquals(AiDecision.SKIP, decide(secure = true))
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
