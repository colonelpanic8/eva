package com.colonelpanic.eva.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.colonelpanic.eva.conversation.ConversationEntry
import com.colonelpanic.eva.conversation.DeviceStep
import com.colonelpanic.eva.conversation.EntryGroup
import com.colonelpanic.eva.conversation.EntryStatus
import com.colonelpanic.eva.conversation.QuestionResolution
import com.colonelpanic.eva.conversation.TextLegDetails
import com.colonelpanic.eva.conversation.TurnStatus
import com.colonelpanic.eva.providers.ProviderToolCatalog

internal fun Constraints.withMaxWidthFraction(fraction: Float): Constraints {
    if (!hasBoundedWidth) return this
    val maxWidth = (maxWidth * fraction).toInt()
    return copy(minWidth = minOf(minWidth, maxWidth), maxWidth = maxWidth)
}

private fun Modifier.maxWidthFraction(fraction: Float): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.withMaxWidthFraction(fraction))
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

/**
 * One turn: the request, the actions the model ran for it on a branch beneath, then the
 * answer. A session marker renders as a divider so each session reads as its own thread.
 */
@Composable
internal fun ConversationEntryItem(
    entry: ConversationEntry,
    children: List<EntryGroup> = emptyList(),
    onOpenExtensions: () -> Unit = {},
) {
    if (entry.status == EntryStatus.SESSION) {
        SessionDivider(entry.response, onOpenExtensions.takeIf { entry.response.endsWith(ProviderToolCatalog.SEE_EXTENSIONS) })
        return
    }
    entry.question?.let {
        QuestionExchange(it)
        return
    }
    entry.textLeg?.let {
        TextLegBlock(entry, it, children.map(EntryGroup::entry))
        return
    }
    if (entry.capabilityId != null && entry.request.isBlank()) {
        ActionRow(entry)
        return
    }
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
        if (children.isNotEmpty()) {
            ActionBranch {
                children.forEach { child ->
                    key(child.entry.id) {
                        val leg = child.entry.textLeg
                        if (child.entry.question != null) {
                            QuestionExchange(checkNotNull(child.entry.question))
                        } else if (leg == null) {
                            ActionRow(child.entry)
                        } else {
                            TextLegBlock(child.entry, leg, child.actions)
                        }
                    }
                }
            }
        }
        if (entry.response.isNotBlank()) ResponseBubble(entry)
    }
}

@Composable
private fun ResponseBubble(entry: ConversationEntry) {
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
            if (entry.status != EntryStatus.ANSWER) StatusLine(entry.status.presentation())
            entry.actionTitle?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
            Text(text = entry.response, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Actions hang off a rail under the request, so what the model did reads as a branch of the turn. */
@Composable
private fun ActionBranch(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(start = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(modifier = Modifier.width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

/**
 * Work a separate text model did for the turn: what it was asked, the actions it ran, and on
 * request the exact instructions it was opened with. Open while it works unless toggled.
 */
@Composable
private fun TextLegBlock(
    entry: ConversationEntry,
    leg: TextLegDetails,
    actions: List<ConversationEntry>,
) {
    var toggled by rememberSaveable(entry.id) { mutableStateOf<Boolean?>(null) }
    val expanded = toggled ?: (leg.status == TurnStatus.OPEN)
    var showPrompt by rememberSaveable(entry.id + ":prompt") { mutableStateOf(false) }
    val status = leg.presentation()
    val actionCount = actions.count { it.question == null }
    val waitingForAnswer = actions.any { it.question?.waiting == true }
    val task = leg.task ?: "Finish the request after the call ended"
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier =
            Modifier
                .maxWidthFraction(0.96f)
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .animateContentSize(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Surface(onClick = { toggled = !expanded }, color = Color.Transparent) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatusIndicator(status)
                        Text(
                            text =
                                when (actionCount) {
                                    0 -> "Text agent"
                                    1 -> "Text agent · 1 action"
                                    else -> "Text agent · $actionCount actions"
                                },
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            text = if (waitingForAnswer) "Waiting for your answer" else status.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = status.color(),
                        )
                    }
                    Text(
                        text = task,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = if (expanded) 0.dp else 8.dp),
                    )
                }
            }
            if (expanded) {
                if (actions.isNotEmpty()) {
                    Box(modifier = Modifier.padding(end = 12.dp)) {
                        ActionBranch {
                            actions.forEach { action ->
                                key(action.id) {
                                    action.question?.let { QuestionExchange(it) } ?: ActionRow(action)
                                }
                            }
                        }
                    }
                }
                Surface(onClick = { showPrompt = !showPrompt }, color = Color.Transparent) {
                    Text(
                        text = if (showPrompt) "Hide prompt" else "Show prompt",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                if (showPrompt) {
                    Column(
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        DetailText("Instructions, followed by the last ${leg.historyItems} conversation items:")
                        SelectionContainer {
                            Text(
                                text = leg.instructions,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One line per action; tapping it shows the arguments the model sent and the full result. */
@Composable
private fun ActionRow(action: ConversationEntry) {
    var expanded by rememberSaveable(action.id) { mutableStateOf(false) }
    val status = action.status.presentation()
    val title = action.actionTitle ?: action.capabilityId ?: "Action"
    Surface(
        onClick = { expanded = !expanded },
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(12.dp),
        modifier =
            Modifier
                .maxWidthFraction(0.92f)
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .animateContentSize(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusIndicator(status)
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = status.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = status.color(),
                )
            }
            if (expanded) {
                action.capabilityId?.let { DetailText(it) }
                action.initiator?.let { origin ->
                    DetailText("from: ${origin.kind.label}")
                    origin.legId?.let { DetailText("leg: $it") }
                }
                action.arguments.forEach { (name, value) -> DetailText("$name: $value") }
                if (action.arguments.isNotEmpty()) HorizontalDivider()
                Text(text = action.response, style = MaterialTheme.typography.bodyMedium)
            } else {
                (action.result ?: action.response).lineSequence().firstOrNull { it.isNotBlank() }?.let { DetailText(it, maxLines = 1) }
            }
            if (action.deviceSteps.isNotEmpty()) DeviceSteps(action.deviceSteps, status.inProgress, expanded)
        }
    }
}

/** Every screen read and input a device task made, with the backend that served it; tap the row for the worker's reasons. */
@Composable
private fun DeviceSteps(
    steps: List<DeviceStep>,
    running: Boolean,
    expanded: Boolean,
) {
    HorizontalDivider()
    val backends = steps.mapNotNull { it.backend }.distinct()
    DetailText(
        "${steps.size} ${if (steps.size == 1) "step" else "steps"}" + if (backends.isEmpty()) "" else " · ${backends.joinToString(" → ")}",
    )
    steps.forEachIndexed { index, step ->
        val status = step.presentation(running && index == steps.lastIndex)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.padding(top = 4.dp)) { StatusIndicator(status) }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${step.step}. ${step.detail}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (status.label.isNotEmpty()) {
                    Text(
                        text = status.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = status.color(),
                    )
                }
                if (expanded) step.intent?.let { DetailText(it) }
            }
        }
    }
}

private fun DeviceStep.presentation(current: Boolean): StatusPresentation =
    when {
        result == "ok" -> {
            StatusPresentation("", inProgress = false) { MaterialTheme.colorScheme.primary }
        }

        kind == "finish" && result == "completed" -> {
            StatusPresentation(
                "Completed",
                inProgress = false,
            ) { MaterialTheme.colorScheme.primary }
        }

        result == "asked" -> {
            StatusPresentation(if (current) "Waiting for your answer" else "Asked", inProgress = current) {
                MaterialTheme.colorScheme.tertiary
            }
        }

        result == "not_dispatched" && current -> {
            StatusPresentation("Running", inProgress = true) { MaterialTheme.colorScheme.tertiary }
        }

        result == "not_dispatched" -> {
            StatusPresentation("Not run", inProgress = false) { MaterialTheme.colorScheme.onSurfaceVariant }
        }

        else -> {
            StatusPresentation(result.ifBlank { "Failed" }, inProgress = false) { MaterialTheme.colorScheme.error }
        }
    }

@Composable
private fun DetailText(
    text: String,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun SessionDivider(
    label: String,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClickLabel = "Open Extensions", onClick = onClick) else Modifier)
                .padding(vertical = 4.dp)
                .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(4f, fill = false),
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun StatusIndicator(status: StatusPresentation) {
    if (status.inProgress) {
        CircularProgressIndicator(
            modifier = Modifier.size(12.dp),
            strokeWidth = 2.dp,
            color = status.color(),
        )
    } else {
        Box(modifier = Modifier.size(10.dp).background(status.color(), CircleShape))
    }
}

@Composable
internal fun StatusLine(status: StatusPresentation) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusIndicator(status)
        Text(
            text = status.label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = status.color(),
        )
    }
}

internal class StatusPresentation(
    val label: String,
    val inProgress: Boolean,
    val color: @Composable () -> Color,
)

internal fun TextLegDetails.presentation(): StatusPresentation =
    when (status) {
        TurnStatus.OPEN -> StatusPresentation("Working", inProgress = true) { MaterialTheme.colorScheme.tertiary }
        TurnStatus.ANSWERED -> StatusPresentation("Done", inProgress = false) { MaterialTheme.colorScheme.primary }
        TurnStatus.INTERRUPTED -> StatusPresentation("Interrupted", inProgress = false) { MaterialTheme.colorScheme.onSurfaceVariant }
        TurnStatus.FAILED -> StatusPresentation("Failed", inProgress = false) { MaterialTheme.colorScheme.error }
    }

internal fun EntryStatus.presentation(): StatusPresentation =
    when (this) {
        EntryStatus.ANSWER, EntryStatus.SESSION -> {
            StatusPresentation("", false) { MaterialTheme.colorScheme.onSurface }
        }

        EntryStatus.PENDING -> {
            StatusPresentation("Preparing", inProgress = true) { MaterialTheme.colorScheme.tertiary }
        }

        EntryStatus.DISPATCHING -> {
            StatusPresentation("Running", inProgress = true) { MaterialTheme.colorScheme.tertiary }
        }

        EntryStatus.HANDED_OFF -> {
            StatusPresentation(
                "Handed off",
                inProgress = false,
            ) { MaterialTheme.colorScheme.primary }
        }

        EntryStatus.COMPLETED -> {
            StatusPresentation("Done", inProgress = false) { MaterialTheme.colorScheme.primary }
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

@Composable
internal fun QuestionExchange(question: com.colonelpanic.eva.conversation.QuestionEvidence) {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(question.question, style = MaterialTheme.typography.bodyMedium)
        question.answer?.let { Text("Answer: $it", style = MaterialTheme.typography.bodyMedium) }
        Text(
            when (question.resolution) {
                QuestionResolution.PENDING -> "Waiting for your answer"
                QuestionResolution.SUBMITTING -> "Sending answer…"
                QuestionResolution.ACCEPTED -> "Answered"
                QuestionResolution.FAILED -> "Couldn't deliver"
                QuestionResolution.CANCELLED -> "Cancelled"
                QuestionResolution.INTERRUPTED -> "Interrupted"
            },
            style = MaterialTheme.typography.labelMedium,
            color =
                when (question.resolution) {
                    QuestionResolution.PENDING, QuestionResolution.SUBMITTING -> MaterialTheme.colorScheme.tertiary
                    QuestionResolution.ACCEPTED -> MaterialTheme.colorScheme.primary
                    QuestionResolution.FAILED -> MaterialTheme.colorScheme.error
                    QuestionResolution.CANCELLED, QuestionResolution.INTERRUPTED -> MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }
}
