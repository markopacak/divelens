package com.markopacak.divelens.licensing

import com.markopacak.divelens.licensing.TestStore.Companion.full
import com.markopacak.divelens.licensing.TestStore.Companion.tip
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LicenceTest {
    private val flags = MapFlags()
    private val store = TestStore()

    private fun TestScope.licence() = Licence(store, flags, backgroundScope).also { testScheduler.runCurrent() }

    @Test fun theRememberedFlagSetsTheStartState() = runTest(UnconfinedTestDispatcher()) {
        assertEquals(LicenceState(full = false), licence().state.value)
        flags.set(Licence.KEY_FULL, true)
        assertEquals(LicenceState(full = true), licence().state.value) // offline: nothing asked yet
    }

    @Test fun anUnansweredRefreshChangesNothing() = runTest(UnconfinedTestDispatcher()) {
        flags.set(Licence.KEY_FULL, true)
        val licence = licence()
        store.owned = null
        assertFalse(licence.refresh())
        assertTrue(licence.state.value.full)
        assertTrue(flags.get(Licence.KEY_FULL))
    }

    @Test fun anUnacknowledgedPurchaseUnlocksAndIsAcknowledgedOnce() = runTest(UnconfinedTestDispatcher()) {
        val licence = licence()
        store.owned = Owned(listOf(full(acknowledged = false)))
        assertTrue(licence.refresh())
        assertEquals(LicenceState(full = true), licence.state.value)
        assertTrue(flags.get(Licence.KEY_FULL))
        licence.refresh()
        assertEquals(listOf(Products.FULL_VERSION), store.finished.map { it.productId })
    }

    @Test fun aPendingPaymentDoesNotUnlock() = runTest(UnconfinedTestDispatcher()) {
        val licence = licence()
        store.owned = Owned(listOf(full(acknowledged = false, state = StorePurchase.State.Pending)))
        licence.refresh()
        assertEquals(LicenceState(full = false, pending = true), licence.state.value)
        assertTrue(store.finished.isEmpty()) // a pending purchase is never acknowledged
    }

    @Test fun aLaterAnswerWithoutThePurchaseRevokes() = runTest(UnconfinedTestDispatcher()) {
        val licence = licence()
        store.owned = Owned(listOf(full()))
        licence.refresh()
        store.owned = Owned(emptyList()) // refunded
        licence.refresh()
        assertEquals(LicenceState(full = false), licence.state.value)
        assertFalse(flags.get(Licence.KEY_FULL))
    }

    @Test fun anUpdateFromPendingToPurchasedUnlocks() = runTest(UnconfinedTestDispatcher()) {
        val licence = licence()
        store.updates.emit(Owned(listOf(full(acknowledged = false, state = StorePurchase.State.Pending))))
        assertEquals(LicenceState(full = false, pending = true), licence.state.value)
        store.updates.emit(Owned(listOf(full(acknowledged = false))))
        assertEquals(LicenceState(full = true), licence.state.value)
        assertEquals(1, store.finished.size)
    }

    @Test fun anUpdateWithOnlyATipNeverRevokes() = runTest(UnconfinedTestDispatcher()) {
        flags.set(Licence.KEY_FULL, true)
        val licence = licence()
        store.updates.emit(Owned(listOf(tip("tip_small", "t1"))))
        assertTrue(licence.state.value.full)
    }

    @Test fun unfinishedTipsAreConsumedOnRefresh() = runTest(UnconfinedTestDispatcher()) {
        val licence = licence()
        store.owned = Owned(listOf(full(), tip("tip_medium", "t1"), tip("tip_large", "t2")))
        licence.refresh()
        assertEquals(listOf("tip_medium", "tip_large"), store.finished.map { it.productId })
        assertTrue(licence.state.value.full) // tips grant nothing, the full version still counts
    }

    @Test fun aFailedAcknowledgeIsRetriedAtTheNextRefresh() = runTest(UnconfinedTestDispatcher()) {
        val failing = object : Store by store {
            var fail = true
            override suspend fun finish(purchase: StorePurchase) { if (fail) error("offline") else store.finish(purchase) }
        }
        val licence = Licence(failing, flags, backgroundScope)
        store.owned = Owned(listOf(full(acknowledged = false)))
        licence.refresh()
        assertTrue(licence.state.value.full)
        failing.fail = false
        licence.refresh()
        assertEquals(1, store.finished.size)
    }
}
