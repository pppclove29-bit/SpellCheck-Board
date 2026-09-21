package com.typeright.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.typeright.app.ime.ImeStatus
import com.typeright.keyboard.FeatureFlags
import com.typeright.keyboard.TypeRightServices
import com.typeright.keyboard.analytics.Events
import com.typeright.keyboard.analytics.UserProperty
import com.typeright.keyboard.account.AccountState
import com.typeright.keyboard.auth.AuthState
import com.typeright.keyboard.settings.TypeRightSettings
import kotlinx.coroutines.flow.first

enum class AppTab(val label: String, val icon: String) {
    ONBOARDING("시작하기", "⌨️"),
    SHORTCUTS("단축어", "⚡"),
    SETTINGS("설정", "⚙️"),
    PRO("PRO", "👑"),
}

@Composable
fun TypeRightApp(requestedTab: AppTab?, onRequestedTabConsumed: () -> Unit, focusTick: Int) {
    val context = LocalContext.current
    val services = remember { TypeRightServices.get(context) }
    // 결제가 없는 빌드에서 PRO 탭은 살 수 없는 상품만 보여 주는 막다른 길이라 탭 자체를 뺀다.
    val tabs = remember { AppTab.entries.filter { it != AppTab.PRO || FeatureFlags.billing } }
    var tab by rememberSaveable { mutableStateOf(AppTab.ONBOARDING) }
    val settings by services.settings.settings.collectAsStateWithLifecycle(initialValue = TypeRightSettings())
    val account by services.account.account.collectAsStateWithLifecycle(initialValue = AccountState())
    val authState by services.auth.authState.collectAsStateWithLifecycle(initialValue = AuthState.SignedOut)

    LaunchedEffect(requestedTab) {
        if (requestedTab != null) {
            tab = requestedTab
            onRequestedTabConsumed()
        }
    }

    // Host-app open (already signed in): cache /v1/me (PRO + quota), then sync custom shortcuts with Supabase.
    // Fresh sign-ins do the same inside GoogleSignInState (which also reports restored shortcuts).
    LaunchedEffect(Unit) {
        if (FeatureFlags.cloud && services.auth.authState.first().isSignedIn) {
            services.account.refresh()
            services.shortcutSync.sync(services.account.current().isPro)
        }
    }

    // The metric that decides continue/pivot: is TypeRight actually somebody's keyboard? Re-read on every
    // return to the app (focusTick), since the user may have just switched it in system settings.
    LaunchedEffect(focusTick) {
        val status = ImeStatus.read(context)
        services.analytics.log(Events.imeStatus(enabled = status.enabled, isDefault = status.selected))
    }

    // Segments for retention, so PRO/free and signed-in/out can be compared without joining on a user id.
    LaunchedEffect(account.isPro, authState.isSignedIn, settings.feedbackMode) {
        services.analytics.setUserProperty(UserProperty.PLAN, if (account.isPro) "pro" else "free")
        services.analytics.setUserProperty(UserProperty.SIGNED_IN, authState.isSignedIn.toString())
        services.analytics.setUserProperty(UserProperty.FEEDBACK_MODE, settings.feedbackMode.apiValue)
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Text(t.icon) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                AppTab.ONBOARDING -> OnboardingScreen(services, settings, authState, focusTick)
                AppTab.SHORTCUTS -> ShortcutsScreen(services, settings, account, onOpenPro = { tab = AppTab.PRO })
                AppTab.SETTINGS -> SettingsScreen(services, settings, account, authState)
                AppTab.PRO -> SubscriptionScreen(services, account)
            }
        }
    }
}
