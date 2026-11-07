package com.markopacak.divelens.licensing

import com.markopacak.divelens.ui.help.HelpPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** [full]: the full version is active. [pending]: a full-version payment is waiting to complete. */
data class LicenceState(val full: Boolean, val pending: Boolean = false)

/**
 * Whether the full version is active (add-free-limits-and-purchase D3). The answer is remembered on the phone, so the
 * app works offline, and only a successful answer from the store changes it.
 */
class Licence(
    private val store: Store,
    private val flags: HelpPrefs.Flags,
    scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(LicenceState(full = flags.get(KEY_FULL)))
    val state: StateFlow<LicenceState> = _state.asStateFlow()

    init {
        // Updates carry only the purchases that changed, so they unlock but never revoke.
        scope.launch { store.updates.collect { apply(it, complete = false) } }
    }

    /**
     * Asks the store for everything the user owns. Returns false when it couldn't be asked, which changes nothing.
     * Called at process start and whenever the app comes to the foreground.
     */
    suspend fun refresh(): Boolean {
        val owned = store.owned() ?: return false
        apply(owned, complete = true)
        return true
    }

    /** A purchase the user just completed in the store's sheet. */
    suspend fun onPurchased(purchase: StorePurchase) = apply(Owned(listOf(purchase)), complete = false)

    /** Sets the full version directly. Only the debug developer panel uses it. */
    fun override(full: Boolean) = remember(LicenceState(full))

    private suspend fun apply(owned: Owned, complete: Boolean) {
        val full = owned.purchases.filter { it.productId == Products.FULL_VERSION }
        val paid = full.firstOrNull { it.state == StorePurchase.State.Purchased }
        val pending = paid == null && full.any { it.state == StorePurchase.State.Pending }
        // Acknowledge on every answer until it holds: Play refunds a purchase not acknowledged within 3 days.
        if (paid != null && !paid.acknowledged) finishQuietly(paid)
        // Tips grant nothing; one left unconsumed (say, after a crash) would block the next donation of that amount.
        owned.purchases.filter { it.productId in Products.TIPS && it.state == StorePurchase.State.Purchased }
            .forEach { finishQuietly(it) }
        val now = _state.value
        remember(
            when {
                paid != null -> LicenceState(full = true)
                complete -> LicenceState(full = false, pending = pending) // no purchase: a refund or chargeback
                else -> now.copy(pending = now.pending || pending)
            },
        )
    }

    private fun remember(state: LicenceState) {
        if (state.full != flags.get(KEY_FULL)) flags.set(KEY_FULL, state.full)
        _state.value = state
    }

    private suspend fun finishQuietly(purchase: StorePurchase) {
        try {
            store.finish(purchase)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Retried at the next refresh.
        }
    }

    companion object {
        const val KEY_FULL = "full_version"
    }
}
