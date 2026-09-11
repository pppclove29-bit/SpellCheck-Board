package com.typeright.app.ui

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.typeright.app.ads.RewardedAdProvider
import com.typeright.app.ads.RewardedAdResult
import com.typeright.app.ads.StubRewardedAdProvider
import com.typeright.keyboard.TypeRightServices
import com.typeright.keyboard.account.AccountState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Target of the keyboard's `typeright://reward` chip. */
@Composable
fun RewardScreen(
    services: TypeRightServices,
    account: AccountState,
    adProvider: RewardedAdProvider = remember { StubRewardedAdProvider() },
) {
    val activity = LocalContext.current as? Activity
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        ScreenTitle("🎬 AI 훈수 충전", "보상형 광고 1회 시청 = 오늘 AI 훈수 +3회 (하루 최대 5회 시청)")
        AccountSummaryCard(account, services.isDevAuth, onRefresh = { scope.launch { services.account.refresh() } })

        SectionCard {
            Text("무료 플랜은 하루 5회 AI 훈수를 받을 수 있어요.", fontWeight = FontWeight.SemiBold)
            Text(
                "AI가 실제로 오류를 찾아 훈수를 준 경우에만 1회 차감돼요. 기기 안 맞춤법 규칙 검사는 언제나 무료·무제한이에요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                enabled = !busy && activity != null,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val act = activity ?: return@Button
                    busy = true
                    scope.launch {
                        val userId = services.auth.userId()
                        message = when {
                            userId == null -> "로그인 세션을 만들 수 없어요. 네트워크를 확인해 주세요."
                            !adProvider.isAvailable -> "광고 준비 중이에요. (AdMob 연동은 다음 마일스톤)"
                            else -> when (val r = adProvider.showRewardedAd(act, userId)) {
                                RewardedAdResult.Rewarded -> {
                                    // The server credits via the SSV callback, which can lag slightly behind.
                                    services.account.refresh()
                                    delay(3_000)
                                    services.account.refresh()
                                    "충전 완료! AI 훈수 3회가 추가됐어요."
                                }
                                RewardedAdResult.Dismissed -> "광고를 끝까지 보면 충전돼요."
                                RewardedAdResult.NotAvailable -> "지금은 볼 수 있는 광고가 없어요."
                                is RewardedAdResult.Failed -> "광고를 불러오지 못했어요: ${r.message}"
                            }
                        }
                        busy = false
                    }
                },
            ) { Text("광고 보고 AI 훈수 3회 충전") }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
        }

        SectionCard(container = MaterialTheme.colorScheme.surfaceVariant) {
            Text("개발 메모", fontWeight = FontWeight.SemiBold)
            Text(
                "RewardedAdProvider는 현재 스텁이에요. AdMob 연동 시 ServerSideVerificationOptions.userId에 Supabase " +
                    "user id를 넣어야 서버 SSV 콜백(/v1/ads/admob-ssv)이 올바른 사용자에게 충전해요. 키보드(IME) 안에서는 광고를 띄우지 않아요.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
