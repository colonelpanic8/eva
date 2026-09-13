package com.colonelpanic.eva.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.colonelpanic.eva.conversation.ConversationEntry
import com.colonelpanic.eva.conversation.EntryStatus

private fun Modifier.maxWidthFraction(fraction: Float): Modifier =
    layout { measurable, constraints ->
        val maxWidth = (constraints.maxWidth * fraction).toInt()
        val placeable = measurable.measure(constraints.copy(minWidth = minOf(constraints.minWidth, maxWidth), maxWidth = maxWidth))
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

private val sampleRequests = listOf("What can you help me with?", "Find Golden Gate Park on the map")

@Composable
internal fun EmptyConversation(
    onSampleSelected: (String) -> Unit,
    enabled: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Where to next?",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text =
                "Connect to your paired host, then ask EVA a question or request an available phone action.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Tap an example to fill it in. Nothing opens until you send.",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            sampleRequests.forEach { sample ->
                SuggestionChip(
                    onClick = { onSampleSelected(sample) },
                    enabled = enabled,
                    label = { Text(sample) },
                    modifier = Modifier.semantics { contentDescription = "Fill in example: $sample" },
                )
            }
        }
    }
}

@Composable
internal fun StorageErrorBanner(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Action history is unavailable",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun ConversationEntryItem(entry: ConversationEntry) {
    val status = entry.status.presentation()
    Column(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (entry.request.isNotBlank()) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp),
                    modifier = Modifier.maxWidthFraction(0.85f),
                ) {
                    Text(
                        text = entry.request,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier =
                            Modifier
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                                .semantics { contentDescription = "You asked: ${entry.request}" },
                    )
                }
            }
        }
        if (entry.response.isNotBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp),
                modifier = Modifier.maxWidthFraction(0.92f),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (entry.status !in setOf(EntryStatus.ANSWER, EntryStatus.SESSION)) StatusLine(status)
                    entry.actionTitle?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
                    Text(text = entry.response, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun StatusLine(status: StatusPresentation) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (status.inProgress) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 2.dp,
                color = status.color(),
            )
        } else {
            Box(modifier = Modifier.size(10.dp).background(status.color(), CircleShape))
        }
        Text(
            text = status.label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = status.color(),
        )
    }
}

private class StatusPresentation(
    val label: String,
    val inProgress: Boolean,
    val color: @Composable () -> Color,
)

private fun EntryStatus.presentation(): StatusPresentation =
    when (this) {
        EntryStatus.ANSWER, EntryStatus.SESSION -> {
            StatusPresentation("", false) { MaterialTheme.colorScheme.onSurface }
        }

        EntryStatus.PENDING -> {
            StatusPresentation("Preparing", inProgress = true) { MaterialTheme.colorScheme.tertiary }
        }

        EntryStatus.DISPATCHING -> {
            StatusPresentation("Opening app", inProgress = true) { MaterialTheme.colorScheme.tertiary }
        }

        EntryStatus.HANDED_OFF -> {
            StatusPresentation(
                "Handed off",
                inProgress = false,
            ) { MaterialTheme.colorScheme.primary }
        }

        EntryStatus.NOT_EXECUTED -> {
            StatusPresentation("Not run", inProgress = false) { MaterialTheme.colorScheme.onSurfaceVariant }
        }

        EntryStatus.FAILED -> {
            StatusPresentation("Failed", inProgress = false) { MaterialTheme.colorScheme.error }
        }

        EntryStatus.UNKNOWN -> {
            StatusPresentation("Outcome unknown", inProgress = false) { MaterialTheme.colorScheme.error }
        }
    }
