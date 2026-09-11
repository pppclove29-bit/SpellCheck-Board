package com.typeright.app.ime

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.inputmethod.InputMethodManager

/** Whether the TypeRight IME (bundled in this APK) is enabled in system settings and currently selected. */
data class ImeStatus(val enabled: Boolean, val selected: Boolean) {
    companion object {
        fun read(context: Context): ImeStatus {
            val imm = context.getSystemService(InputMethodManager::class.java) ?: return ImeStatus(false, false)
            val mine = imm.enabledInputMethodList.firstOrNull { it.packageName == context.packageName }
                ?: return ImeStatus(enabled = false, selected = false)
            val currentId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                imm.currentInputMethodInfo?.id
            } else {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            }
            return ImeStatus(enabled = true, selected = currentId == mine.id)
        }
    }
}
