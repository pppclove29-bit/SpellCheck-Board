package com.typeright.keyboard.check

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Trailing-edge debouncer. Not thread-safe: call it from one thread (the IME main thread).
 * Time and dispatching come from [scope], so tests drive it with kotlinx-coroutines-test virtual time.
 *
 * - [submit]: (re)starts the quiet period; [action] runs with the latest value after [delayMillis] of silence.
 * - [flush]: runs a pending action immediately (e.g. space bar).
 * - [flushWith]: cancels any pending action and runs immediately with the given value.
 */
class Debouncer<T>(
    private val scope: CoroutineScope,
    private val delayMillis: Long = DEFAULT_DELAY_MS,
    private val action: (T) -> Unit,
) {
    private var job: Job? = null
    private var hasPending = false
    private var pendingValue: T? = null

    val isPending: Boolean get() = hasPending

    fun submit(value: T) {
        job?.cancel()
        pendingValue = value
        hasPending = true
        job = scope.launch {
            delay(delayMillis)
            fire()
        }
    }

    /** Runs the pending action now. Returns false if nothing was pending. */
    fun flush(): Boolean {
        if (!hasPending) return false
        job?.cancel()
        job = null
        fire()
        return true
    }

    fun flushWith(value: T) {
        cancel()
        action(value)
    }

    fun cancel() {
        job?.cancel()
        job = null
        hasPending = false
        pendingValue = null
    }

    private fun fire() {
        if (!hasPending) return
        @Suppress("UNCHECKED_CAST")
        val value = pendingValue as T
        hasPending = false
        pendingValue = null
        action(value)
    }

    companion object {
        const val DEFAULT_DELAY_MS = 300L
    }
}
