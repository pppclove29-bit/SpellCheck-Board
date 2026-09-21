package com.typeright.app.ui

import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.typeright.app.auth.rememberGoogleSignIn
import com.typeright.app.ime.ImeStatus
import com.typeright.keyboard.FeatureFlags
import com.typeright.keyboard.TypeRightServices
import com.typeright.keyboard.auth.AuthState
import com.typeright.keyboard.settings.TypeRightSettings

/**
 * Android onboarding: enable the keyboard in system settings, select it, sign in with Google, optionally turn AI on.
 * (There is no iOS-style "Full Access" on Android; the system shows its standard third-party keyboard warning.)
 */
@Composable
fun OnboardingScreen(
    services: TypeRightServices,
    settings: TypeRightSettings,
    authState: AuthState,
    focusTick: Int,
) {
    val context = LocalContext.current
    var status by remember { mutableStateOf(ImeStatus.read(context)) }
    var testText by remember { mutableStateOf("") }
    val signIn = rememberGoogleSignIn(services)
    var loginSkipped by rememberSaveable { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { status = ImeStatus.read(context) }
    LaunchedEffect(focusTick) { status = ImeStatus.read(context) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        ScreenTitle(
            "TypeRight 시작하기",
            if (FeatureFlags.ai) "맞춤법을 고쳐 주는 AI 키보드를 설정해요." else "맞춤법을 고쳐 주는 키보드를 설정해요. 전부 기기 안에서 동작해요.",
        )

        StepCard(
            number = 1,
            title = "TypeRight 키보드 켜기",
            done = status.enabled,
            description = "시스템 설정의 키보드 목록에서 'TypeRight 맞춤법 키보드'를 켜 주세요.",
            buttonLabel = "키보드 설정 열기",
            onClick = {
                context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
        )

        SectionCard(container = MaterialTheme.colorScheme.secondaryContainer) {
            Text("🔐 켜기 전에 알아 두세요", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "키보드를 켜면 Android가 '이 입력 방법을 사용하면 비밀번호, 신용카드 번호 등 입력하는 모든 텍스트가 " +
                    "수집될 수 있습니다'라는 경고를 보여줘요. 모든 서드파티 키보드에 똑같이 표시되는 시스템 표준 안내예요.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (FeatureFlags.ai) {
                    "• TypeRight는 입력 내용을 저장하지 않아요.\n" +
                        "• 비밀번호·보안 입력란과 시크릿 모드에서는 검사·네트워크를 완전히 끄고 '보안 키패드'로 동작해요.\n" +
                        "• 맞춤법 규칙 검사는 기기 안에서 해요. AI 훈수는 직접 켜고 동의한 경우에만 문장을 서버로 보내요."
                } else {
                    // 온디바이스 전용 빌드: 입력 텍스트가 나가는 경로가 코드에 없다. 문구도 그에 맞춘다.
                    "• TypeRight는 입력 내용을 저장하지 않아요.\n" +
                        "• 비밀번호·보안 입력란과 시크릿 모드에서는 검사를 완전히 끄고 '보안 키패드'로 동작해요.\n" +
                        "• 맞춤법 검사는 전부 기기 안에서 해요. 입력한 문장을 서버로 보내지 않고, 로그인도 필요 없어요."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }

        StepCard(
            number = 2,
            title = "TypeRight 선택하기",
            done = status.selected,
            description = "입력 방법 선택 창에서 TypeRight를 골라 주세요.",
            buttonLabel = "키보드 선택하기",
            enabled = status.enabled,
            onClick = { context.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker() },
        )

        // 온디바이스 전용 빌드: 로그인·AI 단계가 통째로 빠지고 1·2단계만 남는다.
        if (FeatureFlags.auth) when (authState) {
            is AuthState.Dev -> SectionCard {
                Text("3️⃣ 로그인 (개발 모드)", fontWeight = FontWeight.SemiBold)
                Text(
                    "SUPABASE_URL이 비어 있어 로그인 없이 X-Dev-User-Id(${authState.userId})로 동작해요.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            else -> StepCard(
                number = 3,
                title = "Google로 로그인 (선택)",
                done = authState is AuthState.SignedIn || loginSkipped,
                doneText = (authState as? AuthState.SignedIn)?.let { "로그인됨 · ${it.email ?: "Google 계정"}" }
                    ?: "나중에 설정에서 로그인할 수 있어요. 로그인 없이도 키보드와 기기 안 맞춤법 검사는 모두 돼요.",
                description = "로그인하면 AI 훈수·충전·PRO를 쓸 수 있어요. 로그인하지 않아도 기기 안 맞춤법 검사와 " +
                    "매운맛·상냥한 피드백은 그대로 쓸 수 있어요.",
                buttonLabel = if (signIn.busy) "로그인 중…" else "Google로 로그인",
                enabled = !signIn.busy,
                onClick = { signIn.signIn() },
                secondaryLabel = "나중에 하기",
                onSecondary = { loginSkipped = true },
                footer = signIn.message,
            )
        }

        if (FeatureFlags.ai) SectionCard {
            Text("4️⃣ AI 훈수 켜기 (선택)", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            AiToggleRow(
                services,
                settings,
                authState,
                signIn,
                title = "AI 훈수",
                description = "기본값은 꺼짐이에요. 켜면 문장이 끝날 때 그 문장을 AI로 한 번 더 검사해요. " +
                    "처음 켤 때 한 번만 데이터 전송 동의를 받아요. 설정에서 언제든 켜고 끌 수 있어요.",
            )
        }

        SectionCard {
            Text(
                if (status.enabled && status.selected) "✅ 준비 완료! 아래에서 바로 써 보세요." else "✍️ 테스트 입력",
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = testText,
                onValueChange = { testText = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("예: 오늘 몇일이야? 내일 갈께") },
                minLines = 3,
            )
        }
    }
}

@Composable
private fun StepCard(
    number: Int,
    title: String,
    done: Boolean,
    description: String,
    buttonLabel: String,
    enabled: Boolean = true,
    doneText: String? = null,
    footer: String? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (done) "✅" else "$number️⃣", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    if (done) doneText ?: "완료됨" else description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!done) {
            Spacer(Modifier.height(10.dp))
            Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(buttonLabel) }
            if (secondaryLabel != null && onSecondary != null) {
                TextButton(onClick = onSecondary, modifier = Modifier.fillMaxWidth()) { Text(secondaryLabel) }
            }
        }
        footer?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
    }
}
