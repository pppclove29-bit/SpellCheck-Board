package com.typeright.keyboard.ime.ui

import android.content.Context
import android.media.AudioManager
import android.view.View
import com.typeright.keyboard.ime.KeyFeedback
import com.typeright.keyboard.ime.layout.KeyAction

/**
 * Key-click sound.
 *
 * [AudioManager.playSoundEffect] already honours the system's "touch sounds" setting, so the app-level toggle only
 * ever narrows it: sound plays when the user turned it on AND the system allows it.
 */
internal fun playKeySound(view: View, feedback: KeyFeedback, action: KeyAction?) {
    if (!feedback.sound) return
    val audio = view.context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
    val effect = when (action) {
        KeyAction.Backspace -> AudioManager.FX_KEYPRESS_DELETE
        KeyAction.Enter -> AudioManager.FX_KEYPRESS_RETURN
        KeyAction.Space -> AudioManager.FX_KEYPRESS_SPACEBAR
        else -> AudioManager.FX_KEYPRESS_STANDARD
    }
    runCatching { audio.playSoundEffect(effect) }
}
