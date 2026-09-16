package com.typeright.app.billing

import android.app.Activity

/** Play Billing product IDs (subscriptions). */
object SubscriptionProducts {
    const val PRO_MONTHLY = "typeright_pro_monthly" // 월 2,900원
    const val PRO_YEARLY = "typeright_pro_yearly" // 연 19,900원

    /** Must match the subscription ids created in Play Console, spelling included. */
    val ALL = listOf(PRO_MONTHLY, PRO_YEARLY)
}

data class SubscriptionPlan(val id: String, val title: String, val priceLabel: String, val detail: String)

sealed interface PurchaseResult {
    /** Paid AND verified server-side: PRO is on. Refresh `/v1/me` afterwards. */
    data object Purchased : PurchaseResult
    data object Cancelled : PurchaseResult
    data object NotAvailable : PurchaseResult

    /** Paid, but the server could not reach Google to verify. Money is not lost; verification retries later. */
    data object PendingVerification : PurchaseResult

    /** Play reports the purchase as pending (e.g. 미성년자 승인, 무통장). PRO turns on once it clears. */
    data object AwaitingPayment : PurchaseResult

    /** Nothing to restore (no active subscription on this Google account). */
    data object NothingToRestore : PurchaseResult
    data class Failed(val message: String) : PurchaseResult
}

/** Subscription billing. [PlayBillingRepository] is the real implementation; the stub is the fallback. */
interface SubscriptionRepository {
    val plans: List<SubscriptionPlan>
    val isAvailable: Boolean

    suspend fun purchase(activity: Activity, plan: SubscriptionPlan): PurchaseResult

    suspend fun restore(): PurchaseResult
}

class StubSubscriptionRepository : SubscriptionRepository {
    override val plans = listOf(
        SubscriptionPlan(SubscriptionProducts.PRO_MONTHLY, "월간 PRO", "월 2,900원", "매월 자동 갱신"),
        SubscriptionPlan(SubscriptionProducts.PRO_YEARLY, "연간 PRO", "연 19,900원", "월 1,658원꼴 · 약 43% 할인"),
    )
    override val isAvailable: Boolean = false

    override suspend fun purchase(activity: Activity, plan: SubscriptionPlan): PurchaseResult = PurchaseResult.NotAvailable

    override suspend fun restore(): PurchaseResult = PurchaseResult.NotAvailable
}
