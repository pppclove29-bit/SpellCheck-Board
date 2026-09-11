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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.typeright.app.ime.ImeStatus

/**
 * Android onboarding: enable the keyboard in system settings, then select it. (There is no iOS-style "Full Access"
 * on Android; the system shows its standard third-party keyboard privacy warning when enabling.)
 */
@Composable
fun OnboardingScreen(focusTick: Int) {
    val context = LocalContext.current
    var status by remember { mutableStateOf(ImeStatus.read(context)) }
    var testText by remember { mutableStateOf("") }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { status = ImeStatus.read(context) }
    LaunchedEffect(focusTick) { status = ImeStatus.read(context) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        ScreenTitle("TypeRight 시작하기", "맞춤법을 고쳐 주는 AI 키보드를 두 단계로 설정해요.")

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
                "• TypeRight는 입력 내용을 저장하지 않아요.\n" +
                    "• 비밀번호·보안 입력란과 시크릿 모드에서는 검사·네트워크를 완전히 끄고 '보안 키패드'로 동작해요.\n" +
                    "• 맞춤법 규칙 검사는 기기 안에서 해요. AI 훈수는 문장이 끝났을 때 그 문장만 보내며, 전화번호 등 개인정보는 서버에서 가려져요.",
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
    onClick: () -> Unit,
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (done) "✅" else "$number️⃣", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    if (done) "완료됨" else description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!done) {
            Spacer(Modifier.height(10.dp))
            Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(buttonLabel) }
        }
    }
}
