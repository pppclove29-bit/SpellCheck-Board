package com.typeright.keyboard.hangul

/** Operations shared by [HangulComposer] (두벌식) and [CheonjiinComposer] (천지인). */
interface Composer {
    val composingText: String
    val isComposing: Boolean

    /** Jamo-level backspace; null when nothing is composing (delete before the cursor instead). */
    fun backspace(): ComposeResult?

    /** Commits everything composing. */
    fun finish(): ComposeResult

    /** Drops state without committing. */
    fun reset()
}
