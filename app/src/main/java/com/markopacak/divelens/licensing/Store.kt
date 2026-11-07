package com.markopacak.divelens.licensing

import android.app.Activity
import kotlinx.coroutines.flow.Flow

/** The Play Console product IDs (add-free-limits-and-purchase D9). Prices live in Play Console, never in code. */
object Products {
    /** The one-time, non-consumable unlock. */
    const val FULL_VERSION = "full_version"

    /** Consumable donations, smallest first. */
    val TIPS = listOf("tip_small", "tip_medium", "tip_large")

    val ALL = listOf(FULL_VERSION) + TIPS
}

/** One purchase as the store reports it. [token] identifies it when it is acknowledged or consumed. */
data class StorePurchase(
    val productId: String,
    val state: State,
    val acknowledged: Boolean,
    val token: String,
) {
    enum class State { Purchased, Pending }
}

/** The purchases the store reports, either all of them ([owned]) or only those that just changed ([Store.updates]). */
data class Owned(val purchases: List<StorePurchase>)

/** What a purchase attempt ended with, translated from the store (add-free-limits-and-purchase D2). */
sealed interface BuyOutcome {
    data class Purchased(val purchase: StorePurchase) : BuyOutcome
    data object Pending : BuyOutcome
    data object Cancelled : BuyOutcome

    /** The store can't be reached: no Google Play, offline, or a build Play didn't sign. */
    data object Unavailable : BuyOutcome
    data object Failed : BuyOutcome
}

/**
 * The app store, behind a translation layer (add-free-limits-and-purchase D2): Google Play in release builds, a fake
 * store in debug builds. Each build type's `Stores.create(context)` picks the implementation (D1).
 */
interface Store {
    /** Formatted prices by product ID, in the user's currency; missing for products whose price couldn't be loaded. */
    suspend fun prices(ids: List<String>): Map<String, String>

    /** Every purchase the user owns, or null when the store couldn't be asked. Never an empty answer on an error. */
    suspend fun owned(): Owned?

    suspend fun buy(activity: Activity, id: String): BuyOutcome

    /** Purchases that change while the app runs, for example a pending payment that completes. */
    val updates: Flow<Owned>

    /** Acknowledges the full version or consumes a tip. Throws when the store couldn't do it. */
    suspend fun finish(purchase: StorePurchase)
}
