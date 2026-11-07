package com.markopacak.divelens.licensing

import android.content.Context
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.media3.common.util.UnstableApi
import com.markopacak.divelens.AppGraph
import com.markopacak.divelens.DiveLensApp
import com.markopacak.divelens.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The debug build's developer panel (add-free-limits-and-purchase D10, task 4.1). */
@UnstableApi
class DeveloperPanelTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private lateinit var graph: AppGraph
    private lateinit var store: FakeStore

    @Before fun freeVersion() {
        graph = (rule.activity.application as DiveLensApp).graph
        store = graph.appStore as FakeStore
        rule.activity.getSharedPreferences(FakeStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        runBlocking { graph.licence.refresh() }
        graph.counter.set(0)
        rule.waitForIdle()
        rule.onNodeWithTag("open_settings").performClick()
    }

    private fun show(tag: String) = rule.onNodeWithTag("settings_help").performScrollToNode(hasTestTag(tag))

    @Test fun switchesBetweenFreeAndFull() {
        show("dev_full")
        rule.onNodeWithTag("dev_full").performClick()
        rule.waitUntil(5_000) { graph.licence.state.value.full }
        rule.onNodeWithTag("dev_full").performClick()
        rule.waitUntil(5_000) { !graph.licence.state.value.full }
    }

    @Test fun aSilentRefundIsNoticedAtTheNextRefresh() {
        store.grantFullVersion()
        runBlocking { graph.licence.refresh() }
        show("dev_refund")
        rule.onNodeWithTag("dev_refund").performClick()
        assertTrue(graph.licence.state.value.full) // not yet noticed
        runBlocking { graph.licence.refresh() }
        assertFalse(graph.licence.state.value.full)
    }

    @Test fun completesAPendingPayment() {
        store.answerNext(FakeStore.Answer.PayLater)
        rule.runOnUiThread { graph.purchases.buyFullVersion(rule.activity) }
        rule.waitUntil(5_000) { graph.licence.state.value.pending }
        rule.onNodeWithText("OK").performClick() // the "waiting for payment" message
        show("dev_complete_pending")
        rule.onNodeWithTag("dev_complete_pending").performClick()
        rule.waitUntil(5_000) { graph.licence.state.value.full }
    }

    @Test fun setsTodaysCount() {
        show("dev_count_7")
        rule.onNodeWithTag("dev_count_7").performClick()
        assertEquals(3, graph.counter.remaining())
        assertEquals(3, graph.session.state.value.remainingToday)
        show("dev_count_10")
        rule.onNodeWithTag("dev_count_10").performClick()
        assertEquals(0, graph.counter.remaining())
        show("dev_count_0")
        rule.onNodeWithTag("dev_count_0").performClick()
        assertEquals(10, graph.counter.remaining())
    }
}
