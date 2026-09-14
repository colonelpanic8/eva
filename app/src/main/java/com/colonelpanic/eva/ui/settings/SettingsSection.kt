package com.colonelpanic.eva.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * A titled group of settings. The header carries the section name in the primary color
 * so the screen can be scanned by group rather than read row by row.
 */
@Composable
internal fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
        )
        content()
    }
}

/** Free-form content inside a section, indented to the same gutter as the rows. */
@Composable
internal fun SettingsBlock(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

@Composable
internal fun SettingsRow(
    title: String,
    supporting: String? = null,
    onClick: (() -> Unit)? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = trailing,
        leadingContent = leading,
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick),
    )
}

@Composable
internal fun SettingsSwitchRow(
    title: String,
    supporting: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsRow(title = title, supporting = supporting) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
}

@Composable
internal fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(top = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}
