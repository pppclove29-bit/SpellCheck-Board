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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.typeright.app.billing.PlayBillingRepository
import com.typeright.app.billing.PurchaseResult
import com.typeright.app.billing.SubscriptionRepository
import com.typeright.keyboard.TypeRightServices
import com.typeright.keyboard.account.AccountState
import kotlinx.coroutines.launch

/** PRO subscription: Google Play Billing, with the purchase token verified server-side before PRO turns on. */
@Composable
fun SubscriptionScreen(
    services: TypeRightServices,
    account: AccountState,
    billing: SubscriptionRepository = rememberPlayBilling(services),
) {
    val activity = LocalContext.current as? Activity
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun handle(result: PurchaseResult) {
        message = when (result) {
            PurchaseResult.Purchased -> {
                scope.launch { services.account.refresh() } // server granted PRO; re-read /v1/me
                "PRO가 활성화됐어요!"
            }
            PurchaseResult.Cancelled -> "결제를 취소했어요."
            PurchaseResult.NotAvailable -> "이 기기에서는 Google Play 결제를 사용할 수 없어요."
            PurchaseResult.PendingVerification ->
                "결제는 끝났지만 확인이 늦어지고 있어요. 잠시 후 '구매 복원'을 눌러 주세요. (요금은 그대로 유지돼요)"
            PurchaseResult.AwaitingPayment -> "결제 승인 대기 중이에요. 승인되면 PRO가 켜져요."
            PurchaseResult.NothingToRestore -> "복원할 구독이 없어요."
            is PurchaseResult.Failed -> "결제 실패: ${result.message}"
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        ScreenTitle("👑 TypeRight PRO", "구독은 Google Play를 통해 결제되고, 언제든 Play에서 해지할 수 있어요.")
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
                        enabled = billing.isAvailable && activity != null && !account.isPro && !busy,
                        onClick = {
                            val act = activity ?: return@Button
                            scope.launch {
                                busy = true
                                try {
                                    handle(billing.purchase(act, plan))
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    ) { Text(if (account.isPro) "구독 중" else "구독하기") }
                }
            }
        }

        OutlinedButton(
            enabled = !busy,
            onClick = {
                scope.launch {
                    busy = true
                    try {
                        handle(billing.restore())
                    } finally {
                        busy = false
                    }
                }
            },
        ) { Text("구매 복원") }
        message?.let { Text(it, modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall) }
    }
}

/**
 * One billing connection per screen visit. `start()` connects to Play and replaces the placeholder prices with the
 * ones Play reports for the user's country.
 */
@Composable
private fun rememberPlayBilling(services: TypeRightServices): SubscriptionRepository {
    val context = LocalContext.current
    val billing = remember(context) { PlayBillingRepository(context, services) }
    DisposableEffect(billing) {
        onDispose(billing::close)
    }
    LaunchedEffect(billing) { billing.start() }
    return billing
}
