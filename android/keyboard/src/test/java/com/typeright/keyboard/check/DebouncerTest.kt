package com.typeright.keyboard.check

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DebouncerTest {

    @Test
    fun runsOnlyAfterQuietPeriod() = runTest {
        val calls = mutableListOf<Int>()
        val d = Debouncer<Int>(backgroundScope, 300) { calls += it }
        d.submit(1)
        advanceTimeBy(299)
        runCurrent()
        assertEquals(emptyList<Int>(), calls)
        assertTrue(d.isPending)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(1), calls)
        assertFalse(d.isPending)
    }

    @Test
    fun eachSubmitRestartsTimerAndLatestValueWins() = runTest {
        val calls = mutableListOf<Int>()
        val d = Debouncer<Int>(backgroundScope, 300) { calls += it }
        d.submit(1)
        advanceTimeBy(200)
        d.submit(2)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(emptyList<Int>(), calls)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf(2), calls)
        advanceUntilIdle()
        assertEquals(listOf(2), calls)
    }

    @Test
    fun flushRunsPendingImmediatelyOnce() = runTest {
        val calls = mutableListOf<Int>()
        val d = Debouncer<Int>(backgroundScope, 300) { calls += it }
        d.submit(7)
        assertTrue(d.flush())
        assertEquals(listOf(7), calls)
        advanceUntilIdle()
        assertEquals(listOf(7), calls) // timer was cancelled
    }

    @Test
    fun flushWithoutPendingDoesNothing() = runTest {
        val calls = mutableListOf<Int>()
        val d = Debouncer<Int>(backgroundScope, 300) { calls += it }
        assertFalse(d.flush())
        assertEquals(emptyList<Int>(), calls)
    }

    @Test
    fun flushWithCancelsPendingAndRunsNow() = runTest {
        val calls = mutableListOf<Int>()
        val d = Debouncer<Int>(backgroundScope, 300) { calls += it }
        d.submit(1)
        d.flushWith(9) // e.g. space bar
        assertEquals(listOf(9), calls)
        advanceUntilIdle()
        assertEquals(listOf(9), calls)
    }

    @Test
    fun cancelDropsPending() = runTest {
        val calls = mutableListOf<Int>()
        val d = Debouncer<Int>(backgroundScope, 300) { calls += it }
        d.submit(1)
        d.cancel()
        advanceUntilIdle()
        assertEquals(emptyList<Int>(), calls)
        assertFalse(d.isPending)
    }
}
