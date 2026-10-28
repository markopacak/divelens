package com.markopacak.divelens.ui.help

import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.markopacak.divelens.R

/**
 * The "Watermark on exports" switch (add-branding-and-watermark D8), the first entry of the Settings section. The whole
 * row toggles; the change applies from the next export.
 */
@Composable
fun WatermarkSetting(on: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.setting_watermark)) },
        supportingContent = { Text(stringResource(R.string.setting_watermark_summary)) },
        trailingContent = { Switch(checked = on, onCheckedChange = null) },
        modifier = Modifier
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
            .testTag("setting_watermark"),
    )
}
