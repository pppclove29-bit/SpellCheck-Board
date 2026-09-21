package com.typeright.app.ui

import androidx.compose.runtime.Composable
import com.typeright.keyboard.TypeRightServices
import com.typeright.keyboard.account.AccountState

/**
 * `ondevice` 플레이버용 빈 구현.
 *
 * 결제가 없는 빌드에는 Play Billing SDK 도, 실제 [SubscriptionScreen] 도 들어가지 않는다. 다만
 * `TypeRightApp` 의 탭 분기가 이름으로 참조하기 때문에 시그니처만 같은 자리 채움이 필요하다.
 *
 * **실제로 호출되지는 않는다.** PRO 탭 자체가 `FeatureFlags.billing` 으로 걸러져 목록에 없다.
 * cloud 플레이버에서는 이 파일이 빠지고 `src/cloud` 의 진짜 화면이 들어간다.
 */
@Composable
fun SubscriptionScreen(services: TypeRightServices, account: AccountState) {
    // 의도적으로 비어 있다 — 도달할 수 없는 분기다.
}
