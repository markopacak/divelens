package com.markopacak.divelens.licensing

import android.app.Activity
import android.content.SharedPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * The debug build's store (add-free-limits-and-purchase D10): no Google code, no account. Owned purchases are kept in
 * [prefs], so they survive a restart like Play's. [buy] shows [sheet], a stand-in for Play's purchase sheet, and
 * waits for an answer from the overlay, the developer panel or a test ([answerNext]).
 */
class FakeStore(private val prefs: SharedPreferences) : Store {
    /** The answers Play's sheet can end with. */
    enum class Answer { Buy, PayLater, Cancel, Fail }

    /** A purchase waiting for an answer. */
    class Sheet(val productId: String, val price: String?, internal val answer: CompletableDeferred<Answer>)

    private val _sheet = MutableStateFlow<Sheet?>(null)
    val sheet: StateFlow<Sheet?> = _sheet.asStateFlow()

    private val _updates = MutableSharedFlow<Owned>(extraBufferCapacity = 8)
    override val updates: SharedFlow<Owned> = _updates.asSharedFlow()

    /** When false, the store behaves like Play unreachable: no prices, no answer, purchases unavailable. */
    var available: Boolean
        get() = prefs.getBoolean(KEY_AVAILABLE, true)
        set(value) = prefs.edit().putBoolean(KEY_AVAILABLE, value).apply()

    private var queued: Answer? = null

    /** Answers the next purchase without showing the sheet. For instrumented tests. */
    fun answerNext(answer: Answer) {
        _sheet.value?.let { answer(it, answer); return }
        queued = answer
    }

    /** Answers the sheet on screen. */
    fun answer(sheet: Sheet, answer: Answer) {
        if (_sheet.value === sheet) _sheet.value = null
        sheet.answer.complete(answer)
    }

    override suspend fun prices(ids: List<String>): Map<String, String> =
        if (available) PRICES.filterKeys { it in ids } else emptyMap()

    override suspend fun owned(): Owned? = if (available) Owned(load()) else null

    override suspend fun buy(activity: Activity, id: String): BuyOutcome {
        if (!available) return BuyOutcome.Unavailable
        val answer = queued?.also { queued = null } ?: run {
            val sheet = Sheet(id, PRICES[id], CompletableDeferred())
            _sheet.value = sheet
            try {
                sheet.answer.await()
            } finally {
                if (_sheet.value === sheet) _sheet.value = null
            }
        }
        return when (answer) {
            Answer.Buy -> StorePurchase(id, StorePurchase.State.Purchased, acknowledged = false, token = token()).let {
                save(load() + it)
                BuyOutcome.Purchased(it)
            }
            Answer.PayLater -> {
                save(load() + StorePurchase(id, StorePurchase.State.Pending, acknowledged = false, token = token()))
                BuyOutcome.Pending
            }
            Answer.Cancel -> BuyOutcome.Cancelled
            Answer.Fail -> BuyOutcome.Failed
        }
    }

    override suspend fun finish(purchase: StorePurchase) {
        save(load().mapNotNull {
            when {
                it.token != purchase.token -> it
                it.productId == Products.FULL_VERSION -> it.copy(acknowledged = true)
                else -> null // a consumed tip is gone, so the same amount can be given again
            }
        })
    }

    /** The developer panel: owns the full version, as if bought earlier. */
    fun grantFullVersion() {
        save(load().filterNot { it.productId == Products.FULL_VERSION } +
            StorePurchase(Products.FULL_VERSION, StorePurchase.State.Purchased, acknowledged = true, token = token()))
    }

    /** The developer panel: a refund or chargeback; the app notices at its next refresh. */
    fun refundFullVersion() = save(load().filterNot { it.productId == Products.FULL_VERSION })

    /** The developer panel: a pending full-version payment completes while the app runs. */
    fun completePending(): Boolean {
        val pending = load().firstOrNull { it.productId == Products.FULL_VERSION && it.state == StorePurchase.State.Pending } ?: return false
        val paid = pending.copy(state = StorePurchase.State.Purchased)
        save(load().map { if (it.token == pending.token) paid else it })
        _updates.tryEmit(Owned(listOf(paid)))
        return true
    }

    fun hasPending(): Boolean = load().any { it.state == StorePurchase.State.Pending }

    private fun load(): List<StorePurchase> = prefs.getStringSet(KEY_OWNED, emptySet()).orEmpty().mapNotNull(::decode)

    private fun save(purchases: List<StorePurchase>) =
        prefs.edit().putStringSet(KEY_OWNED, purchases.mapTo(HashSet(), ::encode)).apply()

    private fun token() = "fake-" + UUID.randomUUID()

    private fun encode(p: StorePurchase) = listOf(p.productId, p.state.name, p.acknowledged.toString(), p.token).joinToString("|")

    private fun decode(s: String): StorePurchase? = s.split("|").takeIf { it.size == 4 }?.let { (id, state, ack, token) ->
        StorePurchase(id, StorePurchase.State.valueOf(state), ack.toBoolean(), token)
    }

    companion object {
        /** The fake store's own preferences file, apart from the app's. */
        const val PREFS = "divelens_fake_store"
        private const val KEY_OWNED = "owned"
        private const val KEY_AVAILABLE = "available"

        /** Prices as Play would format them; the real ones are set in Play Console. */
        val PRICES = mapOf(
            Products.FULL_VERSION to "CHF 1.19",
            "tip_small" to "CHF 2.00",
            "tip_medium" to "CHF 5.00",
            "tip_large" to "CHF 10.00",
        )
    }
}
