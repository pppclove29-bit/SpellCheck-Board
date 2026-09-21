package com.typeright.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.typeright.app.auth.rememberGoogleSignIn
import com.typeright.keyboard.FeatureFlags
import com.typeright.keyboard.TypeRightServices
import com.typeright.keyboard.account.AccountState
import com.typeright.keyboard.api.ApiResult
import com.typeright.keyboard.auth.AuthState
import com.typeright.keyboard.rules.FeedbackMode
import com.typeright.keyboard.settings.FeedbackModeResolver
import com.typeright.keyboard.settings.KoreanLayout
import com.typeright.keyboard.settings.TypeRightSettings
import kotlinx.coroutines.launch

const val LOGOUT_TEXT = "PRO 단축어는 다시 로그인하면 자동으로 복원돼요."

const val DELETE_ACCOUNT_TEXT =
    "계정을 삭제하면 서버에 저장된 계정 정보(AI 훈수 사용 기록·충전 내역·동기화된 단축어)가 삭제되고 되돌릴 수 없어요.\n\n" +
        "이용 중인 Play 스토어 정기 결제(PRO)는 계정을 삭제해도 자동으로 해지되지 않아요. " +
        "Play 스토어 > 결제 및 정기 결제에서 따로 해지해 주세요."

@Composable
fun SettingsScreen(
    services: TypeRightServices,
    settings: TypeRightSettings,
    account: AccountState,
    authState: AuthState,
) {
    val scope = rememberCoroutineScope()
    val signIn = rememberGoogleSignIn(services)
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    var accountMessage by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        ScreenTitle("설정", "키보드와 앱이 같은 설정을 공유해요. 바꾸면 키보드에 바로 반영돼요.")

        // 온디바이스 전용 빌드: 계정·AI·충전 카드가 통째로 빠진다(로그인 개념 자체가 없다).
        if (FeatureFlags.auth) SectionCard {
            Text("계정", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            when (authState) {
                is AuthState.Dev -> Text(
                    "개발 모드 · X-Dev-User-Id: ${authState.userId}",
                    style = MaterialTheme.typography.bodySmall,
                )
                is AuthState.SignedIn -> {
                    Text("Google 계정: ${authState.email ?: authState.userId}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { confirmLogout = true }) { Text("로그아웃") }
                        TextButton(
                            onClick = { confirmDelete = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) { Text("계정 삭제") }
                    }
                }
                AuthState.SignedOut -> {
                    Text(
                        "로그인하지 않았어요. 키보드와 기기 안 맞춤법 검사, 매운맛·상냥한 피드백은 로그인 없이도 돼요.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { signIn.signIn() }, enabled = !signIn.busy) {
                        Text(if (signIn.busy) "로그인 중…" else "Google로 로그인")
                    }
                }
            }
            (signIn.message ?: accountMessage)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
        }

        if (FeatureFlags.cloud && authState.isSignedIn) {
            AccountSummaryCard(account, services.isDevAuth, onRefresh = { scope.launch { services.account.refresh() } })
            if (FeatureFlags.ads && !account.isPro) RechargeButton(account)
        }

        if (FeatureFlags.ai) SectionCard { AiToggleRow(services, settings, authState, signIn) }

        SectionCard {
            Text("기본 피드백 모드", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "앱별 자동 모드·앱별 지정이 없는 앱에 적용돼요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FeedbackMode.entries.forEach { mode ->
                // 경찰 모드 기본 설명은 PRO 입력 차단을 언급한다. 결제가 없는 빌드에서는 없는 기능을 광고하게 되므로
                // 설명 자체를 이 빌드에서 실제로 되는 동작으로 갈아 끼운다.
                val policeNoPro = mode == FeedbackMode.POLICE && !FeatureFlags.billing
                val description = if (policeNoPro) "오류를 찾으면 사이렌 진동으로 알려 줘요." else mode.description
                val note = when {
                    mode != FeedbackMode.POLICE -> ""
                    // 로그인이 없는 빌드에서는 경찰 모드를 바로 고를 수 있다(추가 안내 없음).
                    !FeatureFlags.auth -> ""
                    !authState.isSignedIn -> "\n로그인이 필요해요."
                    !account.isPro -> "\n무료 플랜: 진동 경고만 (입력 차단은 PRO)"
                    else -> ""
                }
                OptionRow(
                    title = mode.label,
                    description = description + note,
                    selected = settings.feedbackMode == mode,
                    onSelect = {
                        if (mode == FeedbackMode.POLICE && FeatureFlags.auth && !authState.isSignedIn) {
                            // Police mode needs an account: log in first; on cancel the mode stays unchanged.
                            signIn.signIn(then = { services.settings.setFeedbackMode(mode) })
                        } else {
                            scope.launch { services.settings.setFeedbackMode(mode) }
                        }
                    },
                )
            }
        }

        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("앱별 자동 모드", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "카톡·SNS는 매운맛, 슬랙·메일은 상냥한 선생님으로 자동 전환해요. 키보드의 모드 칩(🌶️/🍎/🚨)을 누르면 앱마다 따로 정할 수 있어요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.appAutoMode,
                    onCheckedChange = { on -> scope.launch { services.settings.setAppAutoMode(on) } },
                )
            }
            if (settings.appModeOverrides.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("앱별로 직접 정한 모드", style = MaterialTheme.typography.labelLarge)
                settings.appModeOverrides.entries
                    .sortedBy { FeedbackModeResolver.appLabel(it.key) }
                    .forEach { (pkg, mode) ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(FeedbackModeResolver.appLabel(pkg), modifier = Modifier.weight(1f))
                            Text("${FeedbackModeResolver.emoji(mode)} ${mode.label}", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { scope.launch { services.settings.setAppModeOverride(pkg, null) } }) {
                                Text("초기화")
                            }
                        }
                    }
                TextButton(onClick = { scope.launch { services.settings.clearAppModeOverrides() } }) { Text("모두 초기화") }
            }
        }

        SectionCard {
            Text("키 입력 반응", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("키 진동", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "키를 누를 때 짧게 진동해요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.keyVibration,
                    onCheckedChange = { on -> scope.launch { services.settings.setKeyVibration(on) } },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("키 소리", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "휴대폰 설정의 '터치 사운드'가 꺼져 있으면 이 스위치를 켜도 소리가 나지 않아요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.keySound,
                    onCheckedChange = { on -> scope.launch { services.settings.setKeySound(on) } },
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

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("로그아웃할까요?") },
            text = { Text(LOGOUT_TEXT) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    scope.launch {
                        services.signOut()
                        accountMessage = "로그아웃했어요."
                    }
                }) { Text("로그아웃") }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("취소") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("계정을 삭제할까요?") },
            text = { Text(DELETE_ACCOUNT_TEXT) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        scope.launch {
                            accountMessage = when (val r = services.deleteAccount()) {
                                is ApiResult.Success -> "계정을 삭제했어요."
                                is ApiResult.Failure -> "계정을 삭제하지 못했어요. 네트워크를 확인하고 다시 시도해 주세요. (${r.httpCode ?: r.kind})"
                            }
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
        )
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
