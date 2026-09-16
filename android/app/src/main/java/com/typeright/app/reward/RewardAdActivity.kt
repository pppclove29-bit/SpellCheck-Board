package com.typeright.app.reward

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.typeright.app.ads.AdMobRewardedAdProvider
import com.typeright.app.ads.RewardedAdProvider
import com.typeright.app.ads.RewardedAdResult
import com.typeright.app.ui.theme.TypeRightTheme
import com.typeright.keyboard.TypeRightServices
import com.typeright.keyboard.account.AccountState
import com.typeright.keyboard.account.RewardCreditPoller
import com.typeright.keyboard.analytics.Events
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "광고 보고 훈수 3회 받기" popup, launched by the keyboard's ⚡충전 button (`typeright://reward`, NEW_TASK).
 * Translucent, excluded from recents and in its own task, so finishing it drops the user back into the app they were
 * typing in (MainActivity is never brought up).
 */
class RewardAdActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TypeRightTheme { RewardAdPopup(onClose = ::finish) } }
    }
}

private sealed interface RewardUi {
    data object Idle : RewardUi
    data class Working(val message: String) : RewardUi
    data class Done(val message: String) : RewardUi
    data class Error(val message: String) : RewardUi
}

@Composable
private fun RewardAdPopup(
    onClose: () -> Unit,
    adProvider: RewardedAdProvider? = null,
) {
    val context = LocalContext.current
    val ads = adProvider ?: remember(context) { AdMobRewardedAdProvider(context) }
    val activity = context as? Activity
    val services = remember { TypeRightServices.get(context) }
    val account by services.account.account.collectAsStateWithLifecycle(initialValue = AccountState())
    val scope = rememberCoroutineScope()
    var ui by remember { mutableStateOf<RewardUi>(RewardUi.Idle) }

    LaunchedEffect(Unit) { services.account.refresh() }

    fun watchAd() {
        val act = activity ?: return
        scope.launch {
            ui = RewardUi.Working("광고를 불러오는 중…")
            // AdMob SSV userId must be the Supabase user id (the server credits that user).
            val userId = services.auth.userId()
            if (userId == null) {
                ui = RewardUi.Error("로그인이 필요해요. TypeRight 앱에서 Google로 로그인해 주세요.")
                return@launch
            }
            if (!ads.isAvailable) {
                ui = RewardUi.Error("지금은 광고를 불러올 수 없어요.")
                return@launch
            }
            services.account.refresh()
            val bonusBefore = services.account.current().quota?.bonus ?: 0
            when (val r = ads.showRewardedAd(act, userId)) {
                RewardedAdResult.Rewarded -> {
                    ui = RewardUi.Working("충전 확인 중…")
                    // The SSV credit is applied server-side asynchronously: poll /v1/me with backoff (≤ ~10 s).
                    val credited = RewardCreditPoller.awaitBonusIncrease(
                        initialBonus = bonusBefore,
                        fetchQuota = { if (services.account.refresh()) services.account.current().quota else null },
                    )
                    services.analytics.log(Events.rewardAdWatched(credited = credited != null))
                    ui = RewardUi.Done(
                        if (credited != null) {
                            "훈수 3회 충전 완료! 오늘 남은 횟수 ${credited.remaining}회"
                        } else {
                            "충전 확인이 늦어지고 있어요. 잠시 후 자동으로 반영돼요."
                        },
                    )
                    delay(1_500)
                    onClose()
                }
                RewardedAdResult.Dismissed -> ui = RewardUi.Error("광고를 끝까지 봐야 충전돼요.")
                RewardedAdResult.NotAvailable -> ui = RewardUi.Error("지금은 볼 수 있는 광고가 없어요.")
                is RewardedAdResult.Failed -> ui = RewardUi.Error("광고를 불러오지 못했어요: ${r.message}")
            }
        }
    }

    Dialog(onDismissRequest = { if (ui !is RewardUi.Working) onClose() }) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text("⚡ 광고 보고 훈수 3회 받기", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "보상형 광고를 끝까지 보면 오늘 AI 훈수 3회가 추가돼요. (하루 최대 5회)",
                    style = MaterialTheme.typography.bodyMedium,
                )
                account.quota?.let {
                    Text(
                        "오늘 남은 AI 훈수 ${it.remaining}회",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                when (val s = ui) {
                    is RewardUi.Working -> Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.padding(end = 10.dp))
                        Text(s.message)
                    }
                    is RewardUi.Done -> Text(s.message, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
                    is RewardUi.Error -> Text(s.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
                    RewardUi.Idle -> Unit
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClose, enabled = ui !is RewardUi.Working) { Text("닫기") }
                    Button(
                        onClick = ::watchAd,
                        enabled = ui is RewardUi.Idle || ui is RewardUi.Error,
                    ) { Text("광고 보기") }
                }
            }
        }
    }
}
