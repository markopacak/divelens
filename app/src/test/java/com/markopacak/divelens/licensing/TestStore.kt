package com.markopacak.divelens.licensing

import android.app.Activity
import kotlinx.coroutines.flow.MutableSharedFlow

/** A [Store] whose answers the test sets. [finished] records every acknowledge or consume. */
class TestStore : Store {
    /** What [owned] answers; null means the store couldn't be asked. */
    var owned: Owned? = Owned(emptyList())
    var prices: Map<String, String> = emptyMap()
    var buyOutcome: BuyOutcome = BuyOutcome.Cancelled
    val finished = mutableListOf<StorePurchase>()
    override val updates = MutableSharedFlow<Owned>(extraBufferCapacity = 8)

    override suspend fun prices(ids: List<String>) = prices.filterKeys { it in ids }
    override suspend fun owned() = owned
    override suspend fun buy(activity: Activity, id: String) = buyOutcome
    override suspend fun finish(purchase: StorePurchase) {
        finished += purchase
        // The store now reports the full version as acknowledged and no longer reports a consumed tip.
        owned = owned?.let { o ->
            Owned(o.purchases.mapNotNull {
                when {
                    it.token != purchase.token -> it
                    it.productId == Products.FULL_VERSION -> it.copy(acknowledged = true)
                    else -> null
                }
            })
        }
    }

    companion object {
        fun full(acknowledged: Boolean = true, state: StorePurchase.State = StorePurchase.State.Purchased) =
            StorePurchase(Products.FULL_VERSION, state, acknowledged, token = "full-1")

        fun tip(id: String, token: String) = StorePurchase(id, StorePurchase.State.Purchased, acknowledged = false, token = token)
    }
}
