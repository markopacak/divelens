package com.markopacak.divelens.licensing

import android.app.Activity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** What a purchase action tells the user once it ends. A cancelled purchase tells nothing. */
enum class PurchaseMessage { ThanksFull, ThanksDonation, Pending, Unavailable, Failed, Restored, NothingToRestore }

/**
 * Purchases started from the UI (add-free-limits-and-purchase D8): the full version, donations and restoring. It never
 * keeps the [Activity] it is given; Play's purchase sheet needs one per call.
 */
class PurchaseController(
    private val store: Store,
    private val licence: Licence,
    private val scope: CoroutineScope,
) {
    private val _prices = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Play's formatted prices by product ID; a product whose price couldn't be loaded is missing. */
    val prices: StateFlow<Map<String, String>> = _prices.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _messages = Channel<PurchaseMessage>(Channel.BUFFERED)

    /** One-shot messages, shown once each. */
    val messages: Flow<PurchaseMessage> = _messages.receiveAsFlow()

    fun loadPrices() {
        scope.launch { store.prices(Products.ALL).takeIf { it.isNotEmpty() }?.let { _prices.value = it } }
    }

    fun buyFullVersion(activity: Activity) = run {
        when (val outcome = store.buy(activity, Products.FULL_VERSION)) {
            is BuyOutcome.Purchased -> {
                licence.onPurchased(outcome.purchase)
                PurchaseMessage.ThanksFull
            }
            BuyOutcome.Pending -> {
                licence.refresh() // shows "waiting for payment"
                PurchaseMessage.Pending
            }
            else -> outcome.message()
        }
    }

    /** Gives one of [Products.TIPS]; only offered in the full version. Grants nothing. */
    fun donate(activity: Activity, tip: String) = run {
        when (val outcome = store.buy(activity, tip)) {
            is BuyOutcome.Purchased -> {
                // Consumed at once, so the same amount can be given again; a failure is retried at the next refresh.
                try { store.finish(outcome.purchase) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
                PurchaseMessage.ThanksDonation
            }
            BuyOutcome.Pending -> PurchaseMessage.Pending
            else -> outcome.message()
        }
    }

    /** Asks the store again ("Restore purchase"). */
    fun restore() = run {
        when {
            !licence.refresh() -> PurchaseMessage.Unavailable
            licence.state.value.full -> PurchaseMessage.Restored
            else -> PurchaseMessage.NothingToRestore
        }
    }

    private fun BuyOutcome.message(): PurchaseMessage? = when (this) {
        BuyOutcome.Cancelled -> null
        BuyOutcome.Unavailable -> PurchaseMessage.Unavailable
        else -> PurchaseMessage.Failed
    }

    /** Runs one action at a time and posts its message. */
    private fun run(action: suspend () -> PurchaseMessage?) {
        if (_busy.value) return
        _busy.value = true
        scope.launch {
            try {
                action()?.let { _messages.send(it) }
            } finally {
                _busy.value = false
            }
        }
    }
}
