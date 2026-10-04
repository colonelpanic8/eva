package com.colonelpanic.eva.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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

/** Free-form content inside a section, indented to the same gutter as the rows. Loose text reads as secondary. */
@Composable
internal fun SettingsBlock(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
        Column(
            modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
    }
}

/** Explanatory or status text; [error] for a problem the user has to act on. */
@Composable
internal fun SettingsNote(
    text: String,
    modifier: Modifier = Modifier,
    error: Boolean = false,
) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun SettingsRow(
    title: String,
    supporting: String? = null,
    onClick: (() -> Unit)? = null,
    leading: @Composable (() -> Unit)? = null,
    supportingIsError: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent =
            supporting?.let {
                { Text(it, color = if (supportingIsError) MaterialTheme.colorScheme.error else Color.Unspecified) }
            },
        trailingContent = trailing,
        leadingContent = leading,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick),
    )
}

/**
 * A row that discloses more below it: a form, a list of actions, or detail. The disclosed content
 * sits on a tinted panel so it reads as belonging to the row above it.
 */
@Composable
internal fun ExpandableSettingsRow(
    title: String,
    supporting: String?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    leading: @Composable (() -> Unit)? = null,
    supportingIsError: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column {
        SettingsRow(
            title = title,
            supporting = supporting,
            onClick = { onExpandedChange(!expanded) },
            leading = leading,
            supportingIsError = supportingIsError,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ExpandIcon(expanded, if (expanded) "Collapse $title" else "Expand $title")
                trailing?.invoke()
            }
        }
        AnimatedVisibility(expanded) {
            SettingsPanel(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) { content() }
        }
    }
}

@Composable
internal fun SettingsPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
            Column(
                modifier = Modifier.padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
internal fun ExpandIcon(
    expanded: Boolean,
    description: String?,
    modifier: Modifier = Modifier,
) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "expand")
    Icon(
        Icons.Default.KeyboardArrowDown,
        contentDescription = description,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.rotate(rotation),
    )
}

/** A short pill naming a property of the thing beside it, such as an action's effect. */
@Composable
internal fun SettingsLabel(
    text: String,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(6.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

/** The first letter of a name, for an extension that has no app icon of its own. */
@Composable
internal fun LetterAvatar(name: String) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape, modifier = Modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/**
 * A whole-seconds value edited in place. It saves on the keyboard's done key or the check
 * button; [onSave] answers with the reason a value was refused, or null once it is stored.
 */
@Composable
internal fun SecondsSettingRow(
    title: String,
    supporting: String?,
    seconds: Long?,
    onSave: (String) -> String?,
    placeholder: String? = null,
) {
    val saved = seconds?.toString().orEmpty()
    var draft by remember(saved) { mutableStateOf(saved) }
    var error by remember(saved) { mutableStateOf<String?>(null) }
    val save = { error = onSave(draft.trim()) }
    SettingsRow(title = title, supporting = error ?: supporting, supportingIsError = error != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (draft != saved) {
                IconButton(onClick = save) { Icon(Icons.Default.Check, contentDescription = "Save $title") }
            }
            OutlinedTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    error = null
                },
                singleLine = true,
                isError = error != null,
                textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.End),
                placeholder = placeholder?.let { { Text(it) } },
                suffix = { Text("s") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.width(88.dp).semantics { contentDescription = title },
            )
        }
    }
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
