package com.markopacak.divelens.ui.help

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.markopacak.divelens.R
import com.markopacak.divelens.licensing.LicenceState
import com.markopacak.divelens.licensing.Products
import com.markopacak.divelens.licensing.PurchaseMessage
import kotlinx.coroutines.flow.Flow

/** What the purchase entries do; each purchase needs the activity, which the caller supplies. */
class PurchaseActions(
    val buyFullVersion: () -> Unit,
    val restore: () -> Unit,
    val donate: (tip: String) -> Unit,
)

/**
 * The Full version and Donate entries at the top of Support DiveLens (add-free-limits-and-purchase D8). Free: the
 * price and what the full version adds, or "waiting for payment". Full: "Full version active", no purchase.
 * Donate offers the full version first to a free user, and three amounts to a full user.
 */
@Composable
fun PurchaseEntries(licence: LicenceState, prices: Map<String, String>, busy: Boolean, actions: PurchaseActions) {
    val name = stringResource(R.string.app_name)
    var donating by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("purchase_entries")) {
        when {
            licence.full -> Entry(
                stringResource(R.string.full_version_active),
                stringResource(R.string.full_version_active_detail, name),
                Modifier.testTag("full_version_entry"),
            ) {}
            else -> Entry(
                prices[Products.FULL_VERSION]?.let { stringResource(R.string.full_version_price, it) } ?: stringResource(R.string.full_version),
                stringResource(if (licence.pending) R.string.full_version_waiting else R.string.full_version_detail),
                Modifier.testTag("full_version_entry"),
            ) { if (!busy && !licence.pending) actions.buyFullVersion() }
        }
        Entry(stringResource(R.string.restore_purchase), stringResource(R.string.restore_purchase_detail), Modifier.testTag("restore_purchase")) {
            if (!busy) actions.restore()
        }
        Entry(stringResource(R.string.donate), stringResource(R.string.donate_detail, name), Modifier.testTag("donate_entry")) { donating = true }
    }
    if (!donating) return
    if (!licence.full) {
        AlertDialog(
            onDismissRequest = { donating = false },
            title = { Text(stringResource(R.string.donate_free_title, name)) },
            text = { Text(stringResource(R.string.donate_free_message, name)) },
            confirmButton = {
                TextButton(onClick = { donating = false; if (!licence.pending) actions.buyFullVersion() }, modifier = Modifier.testTag("donate_buy_full")) {
                    Text(stringResource(R.string.full_version))
                }
            },
            dismissButton = { TextButton(onClick = { donating = false }) { Text(stringResource(R.string.not_now)) } },
            modifier = Modifier.testTag("donate_free_dialog"),
        )
    } else {
        AlertDialog(
            onDismissRequest = { donating = false },
            title = { Text(stringResource(R.string.donate_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.donate_message, name))
                    Products.TIPS.zip(listOf(R.string.tip_small, R.string.tip_medium, R.string.tip_large)).forEach { (tip, label) ->
                        val text = prices[tip]?.let { stringResource(R.string.tip_with_price, stringResource(label), it) } ?: stringResource(label)
                        OutlinedButton(
                            onClick = { donating = false; actions.donate(tip) },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("donate_$tip"),
                        ) { Text(text) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { donating = false }) { Text(stringResource(R.string.not_now)) } },
            modifier = Modifier.testTag("donate_dialog"),
        )
    }
}

/** Shows each purchase message once, as a dialog over whatever screen is open. */
@Composable
fun PurchaseMessages(messages: Flow<PurchaseMessage>) {
    var shown by rememberSaveable { mutableStateOf<PurchaseMessage?>(null) }
    LaunchedEffect(messages) { messages.collect { shown = it } }
    val message = shown ?: return
    val name = stringResource(R.string.app_name)
    val (title, text) = when (message) {
        PurchaseMessage.ThanksFull -> stringResource(R.string.purchase_thanks_title) to stringResource(R.string.purchase_thanks_full)
        PurchaseMessage.ThanksDonation -> stringResource(R.string.purchase_thanks_title) to stringResource(R.string.purchase_thanks_donation, name)
        PurchaseMessage.Pending -> stringResource(R.string.purchase_pending_title) to stringResource(R.string.purchase_pending)
        PurchaseMessage.Unavailable -> stringResource(R.string.purchase_unavailable_title) to stringResource(R.string.purchase_unavailable)
        PurchaseMessage.Failed -> stringResource(R.string.purchase_failed_title) to stringResource(R.string.purchase_failed)
        PurchaseMessage.Restored -> stringResource(R.string.restore_purchase) to stringResource(R.string.purchase_restored)
        PurchaseMessage.NothingToRestore -> stringResource(R.string.restore_purchase) to stringResource(R.string.purchase_nothing_to_restore)
    }
    AlertDialog(
        onDismissRequest = { shown = null },
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { shown = null }) { Text(stringResource(R.string.ok)) } },
        modifier = Modifier.testTag("purchase_message_${message.name}"),
    )
}
