package com.typeright.keyboard.ime

import android.os.Build
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

/** Text before the cursor and the cursor's absolute position (both read at the same moment). */
data class EditorWindow(val text: String, val cursorAbs: Int)

internal object EditorText {
    /**
     * Reads up to [maxChars] before the cursor plus the absolute cursor position. API 31+ gets both atomically via
     * `getSurroundingText`; older versions combine `getTextBeforeCursor` with `getExtractedText` (falling back to the
     * selection tracked from `onUpdateSelection`).
     */
    fun readBeforeCursor(ic: InputConnection, maxChars: Int, trackedSelStart: Int): EditorWindow? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val st = ic.getSurroundingText(maxChars, 0, 0)
            if (st != null && st.offset >= 0 && st.selectionStart >= 0 && st.selectionStart <= st.text.length) {
                return EditorWindow(st.text.subSequence(0, st.selectionStart).toString(), st.offset + st.selectionStart)
            }
        }
        val before = ic.getTextBeforeCursor(maxChars, 0)?.toString() ?: return null
        val extracted = ic.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 0 }, 0)
        val cursor = if (extracted != null && extracted.selectionStart >= 0) {
            extracted.startOffset + extracted.selectionStart
        } else {
            trackedSelStart
        }
        if (cursor < before.length) return null
        return EditorWindow(before, cursor)
    }
}
