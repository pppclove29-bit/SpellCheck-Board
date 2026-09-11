package com.typeright.app.billing

import android.app.Activity

data class SubscriptionPlan(val id: String, val title: String, val priceLabel: String, val detail: String)

sealed interface PurchaseResult {
    /** Purchase done; the server grants PRO — refresh `/v1/me` afterwards. */
    data object Purchased : PurchaseResult
    data object Cancelled : PurchaseResult
    data object NotAvailable : PurchaseResult
    data class Failed(val message: String) : PurchaseResult
}

/** Subscription billing (Phase 3: Google Play Billing). Interface + stub only; no Play Billing dependency yet. */
interface SubscriptionRepository {
    val plans: List<SubscriptionPlan>
    val isAvailable: Boolean

    suspend fun purchase(activity: Activity, plan: SubscriptionPlan): PurchaseResult

    suspend fun restore(): PurchaseResult
}

class StubSubscriptionRepository : SubscriptionRepository {
    override val plans = listOf(
        SubscriptionPlan("typeright_pro_monthly", "월간 PRO", "월 2,900원", "매월 자동 갱신"),
        SubscriptionPlan("typeright_pro_yearly", "연간 PRO", "연 19,900원", "월 1,658원꼴 · 약 43% 할인"),
    )
    override val isAvailable: Boolean = false

    override suspend fun purchase(activity: Activity, plan: SubscriptionPlan): PurchaseResult = PurchaseResult.NotAvailable

    override suspend fun restore(): PurchaseResult = PurchaseResult.NotAvailable
}
