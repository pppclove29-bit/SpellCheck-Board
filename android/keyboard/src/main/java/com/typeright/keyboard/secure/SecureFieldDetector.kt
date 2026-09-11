package com.typeright.keyboard.secure

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * Decides whether the current field is sensitive. Pure function over `EditorInfo.inputType` / `imeOptions`
 * (the constants are compile-time ints, so this is JVM-unit-testable).
 *
 * Secure: text password, web password, visible password, numeric password, or an editor that asked for
 * no personalized learning (incognito).
 */
object SecureFieldDetector {

    fun isSecure(inputType: Int, imeOptions: Int): Boolean {
        if (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0) return true
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputClass) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    /** Password-like fields (not merely incognito): the keyboard starts on the English layout. */
    fun isPassword(inputType: Int): Boolean = isSecure(inputType, 0)

    fun isSecure(info: EditorInfo?): Boolean = info != null && isSecure(info.inputType, info.imeOptions)
}
