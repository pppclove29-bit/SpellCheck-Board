package com.typeright.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.typeright.keyboard.TypeRightServices
import com.typeright.keyboard.account.AccountState
import com.typeright.keyboard.rules.FeedbackMode
import com.typeright.keyboard.settings.KoreanLayout
import com.typeright.keyboard.settings.TypeRightSettings
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(services: TypeRightServices, settings: TypeRightSettings, account: AccountState) {
    val scope = rememberCoroutineScope()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        ScreenTitle("설정", "키보드와 앱이 같은 설정을 공유해요. 바꾸면 키보드에 바로 반영돼요.")

        AccountSummaryCard(account, services.isDevAuth, onRefresh = { scope.launch { services.account.refresh() } })

        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("AI 훈수 사용", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "문장이 끝나면(. ! ? 줄바꿈) 그 문장만 AI로 한 번 더 검사해요. 끄면 기기 안 규칙 검사만 해요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.aiEnabled,
                    onCheckedChange = { scope.launch { services.settings.setAiEnabled(it) } },
                )
            }
        }

        SectionCard {
            Text("피드백 모드", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            FeedbackMode.entries.forEach { mode ->
                val note = if (mode == FeedbackMode.POLICE && !account.isPro) "\n무료 플랜: 진동 경고만 (입력 차단은 PRO)" else ""
                OptionRow(
                    title = mode.label,
                    description = mode.description + note,
                    selected = settings.feedbackMode == mode,
                    onSelect = { scope.launch { services.settings.setFeedbackMode(mode) } },
                )
            }
        }

        SectionCard {
            Text("한국어 자판", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "키보드의 한/영 키를 길게 눌러도 바꿀 수 있어요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            KoreanLayout.entries.forEach { layout ->
                OptionRow(
                    title = layout.label,
                    description = if (layout == KoreanLayout.DUBEOLSIK) "쿼티 배열 한글 자판" else "ㅣ ㆍ ㅡ 조합 3×4 자판",
                    selected = settings.koreanLayout == layout,
                    onSelect = { scope.launch { services.settings.setKoreanLayout(layout) } },
                )
            }
        }
    }
}

@Composable
private fun OptionRow(title: String, description: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
