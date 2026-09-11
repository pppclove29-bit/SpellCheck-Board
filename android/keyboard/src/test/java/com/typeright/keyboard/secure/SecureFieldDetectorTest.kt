package com.typeright.keyboard.secure

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureFieldDetectorTest {

    @Test
    fun textPasswordVariationsAreSecure() {
        listOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        ).forEach { v -> assertTrue(SecureFieldDetector.isSecure(InputType.TYPE_CLASS_TEXT or v, 0)) }
    }

    @Test
    fun numberPasswordIsSecure() {
        assertTrue(
            SecureFieldDetector.isSecure(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, 0),
        )
    }

    @Test
    fun incognitoFlagIsSecure() {
        assertTrue(SecureFieldDetector.isSecure(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
        assertTrue(
            SecureFieldDetector.isSecure(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
            ),
        )
    }

    @Test
    fun normalFieldsAreNotSecure() {
        listOf(
            InputType.TYPE_CLASS_TEXT,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_NULL,
        ).forEach { t -> assertFalse("inputType=$t", SecureFieldDetector.isSecure(t, EditorInfo.IME_ACTION_SEND)) }
    }

    @Test
    fun variationBitsAreInterpretedPerClass() {
        // 0x10 is TYPE_TEXT_VARIATION_URI for text but TYPE_NUMBER_VARIATION_PASSWORD for numbers.
        assertFalse(SecureFieldDetector.isSecure(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, 0))
        // 0x80 is a text password variation, meaningless for the phone class.
        assertFalse(SecureFieldDetector.isSecure(InputType.TYPE_CLASS_PHONE or 0x80, 0))
    }

    @Test
    fun passwordHelperIgnoresIncognito() {
        assertTrue(SecureFieldDetector.isPassword(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertFalse(SecureFieldDetector.isPassword(InputType.TYPE_CLASS_TEXT))
    }
}
