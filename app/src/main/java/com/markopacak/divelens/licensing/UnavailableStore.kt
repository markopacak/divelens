package com.markopacak.divelens.licensing

import android.app.Activity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** A store that can never be reached: the app stays as it is, and purchases report "not available right now". */
object UnavailableStore : Store {
    override suspend fun prices(ids: List<String>): Map<String, String> = emptyMap()
    override suspend fun owned(): Owned? = null
    override suspend fun buy(activity: Activity, id: String): BuyOutcome = BuyOutcome.Unavailable
    override val updates: Flow<Owned> = emptyFlow()
    override suspend fun finish(purchase: StorePurchase) = Unit
}
