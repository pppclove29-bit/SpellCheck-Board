package com.typeright.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.typeright.app.ui.AppTab
import com.typeright.app.ui.TypeRightApp
import com.typeright.app.ui.theme.TypeRightTheme

class MainActivity : ComponentActivity() {
    private val requestedTab = mutableStateOf<AppTab?>(null)

    /** Bumped when the window regains focus (e.g. the IME picker dialog closed) to refresh the keyboard status. */
    private val focusTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            TypeRightTheme {
                TypeRightApp(
                    requestedTab = requestedTab.value,
                    onRequestedTabConsumed = { requestedTab.value = null },
                    focusTick = focusTick.intValue,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) focusTick.intValue++
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "typeright" && data.host == "reward") requestedTab.value = AppTab.REWARD
    }
}
