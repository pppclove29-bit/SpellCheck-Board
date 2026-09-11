package com.typeright.app.ui

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.typeright.app.billing.PurchaseResult
import com.typeright.app.billing.StubSubscriptionRepository
import com.typeright.app.billing.SubscriptionRepository
import com.typeright.keyboard.TypeRightServices
import com.typeright.keyboard.account.AccountState
import kotlinx.coroutines.launch

/** PRO subscription — Phase 3 placeholder (no Play Billing yet). */
@Composable
fun SubscriptionScreen(
    services: TypeRightServices,
    account: AccountState,
    billing: SubscriptionRepository = remember { StubSubscriptionRepository() },
) {
    val activity = LocalContext.current as? Activity
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }

    fun handle(result: PurchaseResult) {
        message = when (result) {
            PurchaseResult.Purchased -> {
                scope.launch { services.account.refresh() } // server grants PRO; re-read /v1/me
                "PRO가 활성화됐어요!"
            }
            PurchaseResult.Cancelled -> "결제를 취소했어요."
            PurchaseResult.NotAvailable -> "결제는 Phase 3에서 열려요. 조금만 기다려 주세요!"
            is PurchaseResult.Failed -> "결제 실패: ${result.message}"
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        ScreenTitle("👑 TypeRight PRO", "⚠️ Phase 3 준비 중 — 결제 기능은 아직 연결되지 않았어요.")
        AccountSummaryCard(account, services.isDevAuth)

        SectionCard {
            Text("PRO 혜택", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("• AI 훈수 무제한 (공정 사용 한도 하루 300회)")
            Text("• 맞춤법 경찰 모드 풀버전: 교정 전까지 스페이스·엔터·문장부호 입력 차단")
            Text("• 커스텀 단축어 저장 + 클라우드 동기화")
        }

        billing.plans.forEach { plan ->
            SectionCard(container = MaterialTheme.colorScheme.primaryContainer) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(plan.title, fontWeight = FontWeight.SemiBold)
                        Text(plan.priceLabel, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(plan.detail, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        enabled = billing.isAvailable && activity != null && !account.isPro,
                        onClick = {
                            val act = activity ?: return@Button
                            scope.launch { handle(billing.purchase(act, plan)) }
                        },
                    ) { Text(if (billing.isAvailable) "구독하기" else "준비 중") }
                }
            }
        }

        OutlinedButton(onClick = { scope.launch { handle(billing.restore()) } }) { Text("구매 복원") }
        message?.let { Text(it, modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall) }
    }
}
