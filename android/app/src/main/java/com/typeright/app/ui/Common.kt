package com.typeright.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.typeright.keyboard.TypeRightServices
import com.typeright.keyboard.account.AccountState

@Composable
fun ScreenTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(bottom = 12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
fun AccountSummaryCard(account: AccountState, isDevAuth: Boolean, onRefresh: (() -> Unit)? = null) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (account.isPro) "👑 PRO 이용 중" else "무료 플랜",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                val q = account.quota
                Text(
                    when {
                        account.isPro -> "AI 훈수 무제한 · 경찰 모드 풀버전 · 커스텀 단축어"
                        q == null -> "AI 훈수 잔여 횟수를 아직 불러오지 못했어요"
                        else -> "오늘 남은 AI 훈수 ${q.remaining}회 (기본 ${q.limit}회 + 보너스 ${q.bonus}회, 사용 ${q.used}회)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isDevAuth) {
                    Text(
                        "개발 모드: Supabase 미설정 → X-Dev-User-Id 헤더로 인증",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            if (onRefresh != null) OutlinedButton(onClick = onRefresh) { Text("새로고침") }
        }
    }
}

/**
 * ⚡충전 → 보상형 광고 팝업(키보드가 여는 것과 같은 화면). 오늘 쿼터를 다 쓰면 강조된다.
 *
 * 클래스 참조 대신 딥링크로 연다. 광고가 빠진 온디바이스 빌드에서는 `RewardAdActivity` 자체가 APK에 없으므로,
 * 이름으로 참조하면 이 파일이 컴파일되지 않는다(키보드·[맛춤뻡 검사]도 같은 이유로 딥링크를 쓴다).
 */
@Composable
fun RechargeButton(account: AccountState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val empty = account.quota?.remaining == 0
    Button(
        onClick = {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(TypeRightServices.REWARD_DEEP_LINK))
                        .setPackage(context.packageName),
                )
            }
        },
        modifier = modifier.fillMaxWidth(),
        colors = if (empty) {
            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        } else {
            ButtonDefaults.buttonColors()
        },
    ) { Text(if (empty) "⚡ 충전 — 오늘 AI 훈수를 다 썼어요" else "⚡ 광고 보고 훈수 3회 받기") }
}
