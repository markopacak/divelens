package com.markopacak.divelens.licensing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.consumePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Google Play Billing behind [Store] (add-free-limits-and-purchase D2). It only translates: billing results become
 * [BuyOutcome] and [Owned], and any error while asking for purchases is null, never "owns nothing" (D3).
 */
class PlayStore(context: Context) : Store {
    private val _updates = MutableSharedFlow<Owned>(extraBufferCapacity = 8)
    override val updates: SharedFlow<Owned> = _updates.asSharedFlow()

    /** The result of the purchase flow in progress; other results are updates (a pending payment completing). */
    @Volatile private var inFlight: CompletableDeferred<Pair<BillingResult, List<Purchase>?>>? = null

    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener { result, purchases ->
            val waiting = inFlight
            if (waiting != null && !waiting.isCompleted) {
                waiting.complete(result to purchases)
            } else if (result.responseCode == BillingResponseCode.OK && purchases != null) {
                _updates.tryEmit(Owned(purchases.mapNotNull { it.toStore() }))
            }
        }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    private val connecting = Mutex()
    private var started = false

    /**
     * Connects the first time. After that the library reconnects by itself when a call needs it
     * ([BillingClient.Builder.enableAutoServiceReconnection]), and the call's result says whether Play could be reached.
     */
    private suspend fun ready(): Boolean = connecting.withLock {
        if (client.isReady || started) return@withLock true
        started = true
        suspendCancellableCoroutine { cont ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (cont.isActive) cont.resume(result.responseCode == BillingResponseCode.OK)
                }

                override fun onBillingServiceDisconnected() {
                    if (cont.isActive) cont.resume(false)
                }
            })
        }
    }

    private suspend fun details(ids: List<String>): List<ProductDetails>? {
        if (!ready()) return null
        val params = QueryProductDetailsParams.newBuilder().setProductList(ids.map {
            QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.INAPP).build()
        }).build()
        val result = client.queryProductDetails(params)
        return if (result.billingResult.responseCode == BillingResponseCode.OK) result.productDetailsList.orEmpty() else null
    }

    override suspend fun prices(ids: List<String>): Map<String, String> =
        details(ids).orEmpty().mapNotNull { d -> d.oneTimePurchaseOfferDetails?.formattedPrice?.let { d.productId to it } }.toMap()

    override suspend fun owned(): Owned? {
        if (!ready()) return null
        val result = client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build())
        if (result.billingResult.responseCode != BillingResponseCode.OK) return null
        return Owned(result.purchasesList.mapNotNull { it.toStore() })
    }

    override suspend fun buy(activity: Activity, id: String): BuyOutcome {
        val product = details(listOf(id))?.firstOrNull() ?: return BuyOutcome.Unavailable
        val offer = product.oneTimePurchaseOfferDetails ?: return BuyOutcome.Unavailable
        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product).apply {
                offer.offerToken?.let(::setOfferToken)
            }.build(),
        )).build()
        val waiting = CompletableDeferred<Pair<BillingResult, List<Purchase>?>>()
        inFlight = waiting
        try {
            val launched = withContext(Dispatchers.Main) { client.launchBillingFlow(activity, params) }
            if (launched.responseCode != BillingResponseCode.OK) return outcome(launched.responseCode, id, null)
            val (result, purchases) = waiting.await()
            return outcome(result.responseCode, id, purchases)
        } finally {
            inFlight = null
        }
    }

    private suspend fun outcome(code: Int, id: String, purchases: List<Purchase>?): BuyOutcome = when (code) {
        BillingResponseCode.OK -> {
            val purchase = purchases.orEmpty().mapNotNull { it.toStore() }.firstOrNull { it.productId == id }
            when (purchase?.state) {
                StorePurchase.State.Purchased -> BuyOutcome.Purchased(purchase)
                StorePurchase.State.Pending -> BuyOutcome.Pending
                null -> BuyOutcome.Failed
            }
        }
        BillingResponseCode.USER_CANCELED -> BuyOutcome.Cancelled
        // Bought earlier (on this account, maybe another phone): treat it as the purchase it is.
        BillingResponseCode.ITEM_ALREADY_OWNED ->
            owned()?.purchases?.firstOrNull { it.productId == id && it.state == StorePurchase.State.Purchased }
                ?.let { BuyOutcome.Purchased(it) } ?: BuyOutcome.Failed
        BillingResponseCode.SERVICE_UNAVAILABLE, BillingResponseCode.BILLING_UNAVAILABLE, BillingResponseCode.SERVICE_DISCONNECTED,
        BillingResponseCode.SERVICE_TIMEOUT, BillingResponseCode.NETWORK_ERROR, BillingResponseCode.ITEM_UNAVAILABLE,
        -> BuyOutcome.Unavailable
        else -> BuyOutcome.Failed
    }

    override suspend fun finish(purchase: StorePurchase) {
        check(ready()) { "Google Play unavailable" }
        val code = if (purchase.productId == Products.FULL_VERSION) {
            client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.token).build()).responseCode
        } else {
            client.consumePurchase(ConsumeParams.newBuilder().setPurchaseToken(purchase.token).build()).billingResult.responseCode
        }
        check(code == BillingResponseCode.OK) { "billing response $code" }
    }

    private fun Purchase.toStore(): StorePurchase? {
        val state = when (purchaseState) {
            Purchase.PurchaseState.PURCHASED -> StorePurchase.State.Purchased
            Purchase.PurchaseState.PENDING -> StorePurchase.State.Pending
            else -> return null
        }
        val id = products.firstOrNull() ?: return null
        return StorePurchase(id, state, isAcknowledged, purchaseToken)
    }
}
