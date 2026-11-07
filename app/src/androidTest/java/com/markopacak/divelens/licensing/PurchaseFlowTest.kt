package com.markopacak.divelens.licensing

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.media3.common.util.UnstableApi
import com.markopacak.divelens.AppGraph
import com.markopacak.divelens.DiveLensApp
import com.markopacak.divelens.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The Full version entry, Restore and Donate on the debug build's fake store (add-free-limits-and-purchase 4.1, 4.3, 4.4). */
@UnstableApi
class PurchaseFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private lateinit var graph: AppGraph
    private lateinit var store: FakeStore

    @Before fun freeVersion() {
        graph = (rule.activity.application as DiveLensApp).graph
        store = graph.appStore as FakeStore
        rule.activity.getSharedPreferences(FakeStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        runBlocking { graph.licence.refresh() }
        graph.counter.set(0)
        graph.session.refreshRemaining()
        rule.waitForIdle()
    }

    private fun openSettings() {
        rule.onNodeWithTag("open_settings").performClick()
        rule.onNodeWithTag("settings_help").performScrollToNode(hasTestTag("full_version_entry"))
    }

    private fun waitForTag(tag: String) = rule.waitUntil(5_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    @Test fun buyingUnlocksAndThanks() {
        openSettings()
        rule.onNodeWithText("Full version · CHF 1.19").assertIsDisplayed()
        rule.onNodeWithText("No daily limit and 20 items at a time. One-time purchase.").assertIsDisplayed()
        rule.onNodeWithTag("full_version_entry").performClick()
        waitForTag("fake_sheet")
        rule.onNodeWithTag("fake_Buy").performClick()
        waitForTag("purchase_message_ThanksFull")
        rule.onNodeWithText("The full version is active: no daily limit and 20 items at a time.").assertIsDisplayed()
        assertTrue(graph.licence.state.value.full)
        assertEquals(20, graph.session.state.value.limit)
        assertNull(graph.session.state.value.remainingToday)
        rule.onNodeWithText("OK").performClick()
        rule.onNodeWithText("Full version active").assertIsDisplayed()
    }

    @Test fun cancellingChangesNothingAndShowsNoError() {
        store.answerNext(FakeStore.Answer.Cancel)
        openSettings()
        rule.onNodeWithTag("full_version_entry").performClick()
        rule.waitUntil(5_000) { !graph.purchases.busy.value }
        rule.waitForIdle()
        rule.onAllNodesWithTag("purchase_message_Failed").assertCountEquals(0)
        rule.onAllNodesWithTag("purchase_message_Unavailable").assertCountEquals(0)
        assertFalse(graph.licence.state.value.full)
    }

    @Test fun payLaterWaitsThenUnlocksWhenThePaymentCompletes() {
        store.answerNext(FakeStore.Answer.PayLater)
        openSettings()
        rule.onNodeWithTag("full_version_entry").performClick()
        waitForTag("purchase_message_Pending")
        rule.onNodeWithText("OK").performClick()
        rule.onNodeWithText("Waiting for payment. It unlocks as soon as Google Play confirms it.").assertIsDisplayed()
        assertFalse(graph.licence.state.value.full)
        assertTrue(store.completePending())
        rule.waitUntil(5_000) { graph.licence.state.value.full }
        rule.onNodeWithText("Full version active").assertIsDisplayed()
    }

    @Test fun anUnreachableStoreSaysNotAvailable() {
        store.available = false
        openSettings()
        rule.onNodeWithTag("full_version_entry").performClick()
        waitForTag("purchase_message_Unavailable")
        assertFalse(graph.licence.state.value.full)
    }

    @Test fun aRefundReturnsToTheFreeVersionAtTheNextRefresh() {
        store.grantFullVersion()
        runBlocking { graph.licence.refresh() }
        assertTrue(graph.licence.state.value.full)
        store.refundFullVersion()
        rule.runOnUiThread { graph.onForeground() }
        rule.waitUntil(5_000) { !graph.licence.state.value.full }
        assertEquals(10, graph.session.state.value.limit)
        assertEquals(10, graph.session.state.value.remainingToday)
    }

    @Test fun restoreFindsAnEarlierPurchase() {
        store.grantFullVersion() // bought on another phone
        openSettings()
        rule.onNodeWithTag("restore_purchase").performClick()
        waitForTag("purchase_message_Restored")
        assertTrue(graph.licence.state.value.full)
    }

    @Test fun aFreeUserIsOfferedTheFullVersionAndNoAmounts() {
        openSettings()
        rule.onNodeWithTag("donate_entry").performClick()
        rule.onNodeWithTag("donate_free_dialog").assertIsDisplayed()
        rule.onNodeWithText("Donations are possible after that.", substring = true).assertIsDisplayed()
        rule.onAllNodesWithTag("donate_tip_medium").assertCountEquals(0)
        rule.onNodeWithTag("donate_buy_full").performClick()
        waitForTag("fake_sheet") // the full version purchase
        rule.onNodeWithTag("fake_Cancel").performClick()
    }

    @Test fun aFullUserDonatesTheSameAmountTwice() {
        store.grantFullVersion()
        runBlocking { graph.licence.refresh() }
        openSettings()
        repeat(2) {
            rule.onNodeWithTag("donate_entry").performClick()
            rule.onNodeWithText("Medium · CHF 5.00").assertIsDisplayed()
            store.answerNext(FakeStore.Answer.Buy)
            rule.onNodeWithTag("donate_tip_medium").performClick()
            waitForTag("purchase_message_ThanksDonation")
            rule.onNodeWithText("OK").performClick()
        }
        assertTrue(graph.licence.state.value.full) // a donation changes nothing
        assertEquals(emptyList<StorePurchase>(), runBlocking { store.owned() }!!.purchases.filter { it.productId in Products.TIPS })
    }
}
