package com.typeright.app.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import androidx.compose.runtime.mutableStateOf
import com.typeright.keyboard.TypeRightServices
import com.typeright.keyboard.api.ApiResult
import com.typeright.keyboard.api.PlayVerifyResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "TypeRightBilling"

/**
 * Google Play Billing for the PRO subscription.
 *
 * The client never decides entitlement: every purchase token goes to `POST /v1/billing/play/verify`, and PRO comes
 * back from the server. A patched APK can fake [PurchaseResult.Purchased] on screen but cannot make the server
 * grant PRO. Acknowledgement is done on the server after verification (Play auto-refunds unacknowledged purchases
 * after three days); this class acknowledges too, as a fallback for the case where verification is delayed.
 */
class PlayBillingRepository(
    context: Context,
    private val services: TypeRightServices,
) : SubscriptionRepository {

    private val app = context.applicationContext
    private val connectMutex = Mutex()

    /** Set while a purchase flow is running: Play answers through the listener, not the launch call. */
    @Volatile
    private var pending: CompletableDeferred<List<Purchase>?>? = null

    private val listener = PurchasesUpdatedListener { result, purchases ->
        val waiter = pending
        when {
            waiter == null -> Unit // an out-of-band update (e.g. a purchase made on another device)
            result.responseCode == BillingClient.BillingResponseCode.OK -> waiter.complete(purchases.orEmpty())
            result.responseCode == BillingClient.BillingResponseCode.USER_CANCELED -> waiter.complete(null)
            else -> waiter.completeExceptionally(BillingFailure(describe(result)))
        }
    }

    private val client: BillingClient = BillingClient.newBuilder(app)
        .setListener(listener)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    // Compose state: the screen shows placeholder prices and a disabled button until Play answers, then
    // recomposes on its own. Plain fields would leave the button stuck disabled.
    private val plansState = mutableStateOf(StubSubscriptionRepository().plans)
    private val connected = mutableStateOf(false)

    /** Prices come from Play; the stub's labels are the fallback until [start] refreshes them. */
    override val plans: List<SubscriptionPlan> get() = plansState.value

    override val isAvailable: Boolean get() = connected.value

    /** Connects and loads real prices. Call before showing the screen; safe to call repeatedly. */
    suspend fun start(): Boolean {
        if (!connect()) return false
        loadPlans()
        return true
    }

    /** Releases the Play connection; call when the screen goes away. */
    fun close() {
        connected.value = false
        runCatching { client.endConnection() }
    }

    override suspend fun purchase(activity: Activity, plan: SubscriptionPlan): PurchaseResult {
        if (!connect()) return PurchaseResult.NotAvailable
        val details = productDetails(plan.id) ?: return PurchaseResult.NotAvailable
        val offerToken = details.subscriptionOfferDetails?.firstOrNull()?.offerToken
            ?: return PurchaseResult.Failed("구독 상품 정보를 불러오지 못했어요")

        val waiter = CompletableDeferred<List<Purchase>?>()
        pending = waiter
        try {
            val params = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                    listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(details)
                            .setOfferToken(offerToken)
                            .build(),
                    ),
                )
                .build()
            val launch = client.launchBillingFlow(activity, params)
            if (launch.responseCode != BillingClient.BillingResponseCode.OK) {
                return PurchaseResult.Failed(describe(launch))
            }
            val purchases = try {
                waiter.await()
            } catch (e: BillingFailure) {
                return PurchaseResult.Failed(e.message ?: "결제를 완료하지 못했어요")
            } ?: return PurchaseResult.Cancelled
            return redeem(purchases.firstOrNull { plan.id in it.products } ?: purchases.firstOrNull())
        } finally {
            pending = null
        }
    }

    override suspend fun restore(): PurchaseResult {
        if (!connect()) return PurchaseResult.NotAvailable
        val purchases = runCatching {
            client.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build(),
            ).purchasesList
        }.getOrElse {
            Log.w(TAG, "restore query failed", it)
            return PurchaseResult.Failed("구매 내역을 불러오지 못했어요")
        }
        val owned = purchases.firstOrNull { it.purchaseState == Purchase.PurchaseState.PURCHASED }
            ?: return PurchaseResult.NothingToRestore
        return redeem(owned)
    }

    /** Sends the purchase token to the server, which asks Google whether it really entitles PRO. */
    private suspend fun redeem(purchase: Purchase?): PurchaseResult {
        if (purchase == null) return PurchaseResult.Failed("결제 정보를 받지 못했어요")
        if (purchase.purchaseState == Purchase.PurchaseState.PENDING) return PurchaseResult.AwaitingPayment

        val productId = purchase.products.firstOrNull() ?: SubscriptionProducts.PRO_MONTHLY
        val verified = when (val r = services.api.verifyPlayPurchase(productId, purchase.purchaseToken)) {
            is ApiResult.Success -> r.value.result
            is ApiResult.Failure -> {
                Log.w(TAG, "verification call failed: ${r.kind}")
                PlayVerifyResult.UNAVAILABLE
            }
        }
        // Acknowledge whatever Play still considers unacknowledged, even when verification failed: an
        // unacknowledged purchase is refunded after three days, which would cost the user their subscription.
        acknowledgeIfNeeded(purchase)

        return when (verified) {
            PlayVerifyResult.ACTIVATED -> {
                services.account.refresh()
                PurchaseResult.Purchased
            }
            PlayVerifyResult.UNAVAILABLE -> PurchaseResult.PendingVerification
            PlayVerifyResult.ALREADY_CLAIMED ->
                PurchaseResult.Failed("이 구독은 다른 계정에 연결돼 있어요. 해당 계정으로 로그인해 주세요")
            PlayVerifyResult.INACTIVE -> PurchaseResult.Failed("구독이 만료되었거나 취소된 상태예요")
            PlayVerifyResult.NOT_FOUND -> PurchaseResult.Failed("구매 내역을 확인할 수 없어요")
            PlayVerifyResult.UNKNOWN_PRODUCT -> PurchaseResult.Failed("지원하지 않는 상품이에요")
        }
    }

    private suspend fun acknowledgeIfNeeded(purchase: Purchase) {
        if (purchase.isAcknowledged || purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        runCatching {
            client.acknowledgePurchase(
                AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build(),
            )
        }.onFailure { Log.w(TAG, "acknowledge failed", it) }
    }

    private suspend fun connect(): Boolean = connectMutex.withLock {
        if (client.isReady) {
            connected.value = true
            return@withLock true
        }
        val ready = CompletableDeferred<Boolean>()
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                val ok = result.responseCode == BillingClient.BillingResponseCode.OK
                connected.value = ok
                if (!ready.isCompleted) ready.complete(ok)
            }

            override fun onBillingServiceDisconnected() {
                connected.value = false
                if (!ready.isCompleted) ready.complete(false)
            }
        })
        ready.await()
    }

    private suspend fun loadPlans() {
        val details = SubscriptionProducts.ALL.mapNotNull { productDetails(it) }
        if (details.isEmpty()) return
        val fallback = StubSubscriptionRepository().plans.associateBy { it.id }
        plansState.value = details.map { product ->
            val stub = fallback[product.productId]
            SubscriptionPlan(
                id = product.productId,
                title = stub?.title ?: product.title,
                priceLabel = product.formattedPrice() ?: stub?.priceLabel.orEmpty(),
                detail = stub?.detail.orEmpty(),
            )
        }
    }

    private suspend fun productDetails(productId: String): ProductDetails? {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build(),
                ),
            )
            .build()
        return runCatching { client.queryProductDetails(params).productDetailsList }
            .onFailure { Log.w(TAG, "product lookup failed for $productId", it) }
            .getOrNull()
            ?.firstOrNull { it.productId == productId }
    }

    private fun ProductDetails.formattedPrice(): String? =
        subscriptionOfferDetails
            ?.firstOrNull()
            ?.pricingPhases
            ?.pricingPhaseList
            ?.lastOrNull()
            ?.formattedPrice

    private class BillingFailure(message: String) : Exception(message)

    private companion object {
        fun describe(result: BillingResult): String = when (result.responseCode) {
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> "이 기기에서는 Google Play 결제를 쓸 수 없어요"
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> "이미 구독 중이에요. '구매 복원'을 눌러 주세요"
            BillingClient.BillingResponseCode.NETWORK_ERROR,
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
            -> "네트워크 상태를 확인해 주세요"
            else -> result.debugMessage.ifBlank { "결제를 완료하지 못했어요 (${result.responseCode})" }
        }
    }
}
