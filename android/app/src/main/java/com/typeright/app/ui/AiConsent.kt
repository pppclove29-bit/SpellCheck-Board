package com.typeright.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.typeright.app.auth.GoogleSignInState
import com.typeright.keyboard.TypeRightServices
import com.typeright.keyboard.auth.AuthState
import com.typeright.keyboard.settings.TypeRightSettings
import kotlinx.coroutines.launch

object Links {
    /** GitHub Pages(`site/privacy/`)로 배포된다. 페이지 공개 설정은 사람 작업 — human-todo B1 참고. */
    const val PRIVACY_POLICY_URL = "https://pppclove29-bit.github.io/SpellCheck-Board/privacy/"
}

/** Play "prominent disclosure" shown the first time AI features are turned on. */
const val AI_CONSENT_TEXT =
    "AI 교정을 켜면 입력한 문장이 맞춤법 검사를 위해 TypeRight 서버와 OpenAI로 전송됩니다. " +
        "주민등록번호·전화번호·이메일·계좌번호는 전송 전에 가려지며, 비밀번호 입력란의 내용은 전송하지 않습니다."

@Composable
fun AiConsentDialog(onAgree: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI 교정 데이터 전송 안내") },
        text = {
            Column {
                Text(AI_CONSENT_TEXT)
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Links.PRIVACY_POLICY_URL)))
                    }
                }) { Text("개인정보처리방침") }
            }
        },
        confirmButton = { TextButton(onClick = onAgree) { Text("동의하고 켜기") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/**
 * AI on/off switch (AI is OFF by default).
 * - Signed out: turning it on opens the Google login first; if the user cancels, nothing changes.
 * - Consent is asked ONCE — the first time AI is enabled (normally in onboarding). Once `ai_consent_at` exists the
 *   switch turns AI on/off freely. No AI network call ever happens without recorded consent.
 */
@Composable
fun AiToggleRow(
    services: TypeRightServices,
    settings: TypeRightSettings,
    authState: AuthState,
    signIn: GoogleSignInState,
    title: String = "AI 훈수 사용",
    description: String = "문장이 끝나면(. ! ? 줄바꿈) 그 문장만 AI로 한 번 더 검사해요. 끄면 기기 안 규칙 검사만 해요.",
) {
    val scope = rememberCoroutineScope()
    var askConsent by remember { mutableStateOf(false) }

    fun enableSignedIn() {
        scope.launch {
            // Succeeds directly when consent was already recorded; otherwise ask for it once.
            if (!services.settings.setAiEnabled(true)) askConsent = true
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!authState.isSignedIn) {
                Text("켜려면 Google 로그인이 필요해요.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }
        Switch(
            checked = settings.aiActive,
            enabled = !signIn.busy,
            onCheckedChange = { on ->
                when {
                    !on -> scope.launch { services.settings.setAiEnabled(false) }
                    !authState.isSignedIn -> signIn.signIn(then = { enableSignedIn() })
                    else -> enableSignedIn()
                }
            },
        )
    }

    if (askConsent) {
        AiConsentDialog(
            onAgree = {
                askConsent = false
                scope.launch { services.settings.enableAiWithConsent(System.currentTimeMillis()) }
            },
            onDismiss = { askConsent = false },
        )
    }
}
