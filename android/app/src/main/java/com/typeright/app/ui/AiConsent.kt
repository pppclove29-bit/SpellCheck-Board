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
import com.typeright.keyboard.TypeRightServices
import com.typeright.keyboard.settings.TypeRightSettings
import kotlinx.coroutines.launch

object Links {
    /** Placeholder until the real policy page is published. */
    const val PRIVACY_POLICY_URL = "https://typeright.example/privacy"
}

/** Play "prominent disclosure" shown before AI features are turned on. */
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
 * AI on/off switch. AI is OFF by default; turning it on always shows [AiConsentDialog] first and records the consent
 * timestamp together with enabling. No AI network call happens without recorded consent.
 */
@Composable
fun AiToggleRow(
    services: TypeRightServices,
    settings: TypeRightSettings,
    title: String = "AI 훈수 사용",
    description: String = "문장이 끝나면(. ! ? 줄바꿈) 그 문장만 AI로 한 번 더 검사해요. 끄면 기기 안 규칙 검사만 해요.",
) {
    val scope = rememberCoroutineScope()
    var askConsent by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = settings.aiActive,
            onCheckedChange = { on ->
                if (on) askConsent = true else scope.launch { services.settings.disableAi() }
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
