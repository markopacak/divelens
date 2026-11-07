package com.markopacak.divelens.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.markopacak.divelens.AppGraph
import com.markopacak.divelens.R
import com.markopacak.divelens.licensing.FakeStore
import com.markopacak.divelens.ui.help.Entry
import kotlinx.coroutines.launch

/**
 * Debug-build tools (add-free-limits-and-purchase D10): the simulated purchase sheet and the developer panel. The
 * release build has an empty object with the same API, so neither ships.
 */
@UnstableApi
object DebugTools {
    /** The fake store's purchase sheet, shown over the app while a purchase waits for an answer. */
    @Composable
    fun Overlay(graph: AppGraph) {
        val store = graph.appStore as? FakeStore ?: return
        val sheet by store.sheet.collectAsStateWithLifecycle()
        val shown = sheet ?: return
        AlertDialog(
            onDismissRequest = { store.answer(shown, FakeStore.Answer.Cancel) },
            title = { Text(stringResource(R.string.fake_sheet_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.fake_sheet_message, shown.productId, shown.price ?: stringResource(R.string.fake_sheet_no_price)))
                    FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            FakeStore.Answer.Buy to R.string.fake_buy,
                            FakeStore.Answer.PayLater to R.string.fake_pay_later,
                            FakeStore.Answer.Fail to R.string.fake_fail,
                        ).forEach { (answer, label) ->
                            OutlinedButton(onClick = { store.answer(shown, answer) }, modifier = Modifier.testTag("fake_${answer.name}")) {
                                Text(stringResource(label))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { store.answer(shown, FakeStore.Answer.Cancel) }, modifier = Modifier.testTag("fake_Cancel")) {
                    Text(stringResource(R.string.fake_cancel))
                }
            },
            modifier = Modifier.testTag("fake_sheet"),
        )
    }

    /** The Developer section of Settings & help. Null in release builds. */
    val developer: (@Composable (AppGraph) -> Unit)? = { graph -> DeveloperPanel(graph) }
}

@UnstableApi
@Composable
private fun DeveloperPanel(graph: AppGraph) {
    val store = graph.appStore as? FakeStore ?: return
    val scope = rememberCoroutineScope()
    val licence by graph.licence.state.collectAsStateWithLifecycle()
    var available by remember { mutableStateOf(store.available) }
    var count by remember { mutableIntStateOf(graph.counter.count()) }
    var pending by remember { mutableStateOf(store.hasPending()) }
    fun refresh() = scope.launch {
        graph.licence.refresh()
        graph.session.refreshRemaining()
        pending = store.hasPending()
    }
    Column(Modifier.fillMaxWidth().testTag("developer_panel")) {
        Toggle(stringResource(R.string.dev_owns_full), stringResource(R.string.dev_owns_full_detail), licence.full, "dev_full") { on ->
            if (on) store.grantFullVersion() else store.refundFullVersion()
            refresh()
        }
        Entry(stringResource(R.string.dev_refund_silently), stringResource(R.string.dev_refund_silently_detail), Modifier.testTag("dev_refund")) {
            store.refundFullVersion()
        }
        Entry(
            stringResource(R.string.dev_complete_pending),
            if (pending) null else stringResource(R.string.dev_no_pending),
            Modifier.testTag("dev_complete_pending"),
        ) {
            store.completePending()
            pending = store.hasPending()
        }
        Toggle(stringResource(R.string.dev_store_available), stringResource(R.string.dev_store_available_detail), available, "dev_available") { on ->
            store.available = on
            available = on
        }
        Text(
            stringResource(R.string.dev_count, count),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )
        FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0, 7, 10).forEach { n ->
                OutlinedButton(onClick = {
                    graph.counter.set(n)
                    count = n
                    graph.session.refreshRemaining()
                }, modifier = Modifier.testTag("dev_count_$n")) { Text(stringResource(R.string.dev_set_count, n)) }
            }
        }
    }
}

@Composable
private fun Toggle(title: String, detail: String, on: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        trailingContent = { Switch(checked = on, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = on, role = Role.Switch, onValueChange = onChange).testTag(tag),
    )
}
