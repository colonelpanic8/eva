package com.colonelpanic.eva.ui

import android.telephony.PhoneNumberUtils
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.colonelpanic.eva.adapters.android.TwilioTransport
import com.colonelpanic.eva.call.CallCapabilities
import com.colonelpanic.eva.call.CallFloor
import com.colonelpanic.eva.call.CallPhase
import com.colonelpanic.eva.call.CallSpeaker
import com.colonelpanic.eva.call.CallTaskSnapshot
import com.colonelpanic.eva.call.CallTaskState
import com.colonelpanic.eva.call.CallTranscriptItem
import com.colonelpanic.eva.call.EndCause
import com.colonelpanic.eva.call.LegState
import com.colonelpanic.eva.call.LiveCaption
import com.colonelpanic.eva.call.terminal
import com.colonelpanic.eva.conversation.QuestionEvidence
import com.colonelpanic.eva.conversation.QuestionResolution
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Locale

/** What a call card can ask of the conversation screen; every control runs through the dispatcher. */
internal class CallCardControls(
    val onControl: (taskId: String, capabilityId: String, arguments: Map<String, String>) -> Unit = { _, _, _ -> },
    val whisperingTo: String? = null,
    val onWhisper: (taskId: String?) -> Unit = {},
    val currentQuestionId: String? = null,
    val onAnswer: () -> Unit = {},
)

internal val LocalCallCardControls = compositionLocalOf { CallCardControls() }

/**
 * Speech in progress per call task, from [com.colonelpanic.eva.call.CallTaskCoordinator.captions].
 * Cards read it beside their journaled snapshot: captions are UI state only, so the agent's words
 * show as it speaks whether or not the owner listens in, without provisional text being stored.
 */
internal val LocalCallCaptions = compositionLocalOf { emptyMap<String, List<LiveCaption>>() }

/** A call is live for controls until its task ends; hang-up stays offered while the leg may still be up. */
internal val CallTaskSnapshot.live: Boolean get() = !state.terminal

private val CallTaskSnapshot.hangUpOffered: Boolean get() = legState !is LegState.Ended

/**
 * One delegated phone call: who has the floor, the leg and its time, what everyone said, what the
 * agent asked you, and the outcome it claims with the transcript lines it cites.
 *
 * The card renders the journaled [CallTaskSnapshot] (so it survives restarts and shows the same
 * thing in history) plus live captions from [LocalCallCaptions] for speech still in progress. The
 * outcome is always presented as the agent's claim beside the lines it cites, never as verified
 * fact. Controls never act directly: each proposes an owner capability through the dispatcher.
 */
@Composable
internal fun CallCard(
    task: CallTaskSnapshot,
    questions: List<QuestionEvidence>,
) {
    val controls = LocalCallCardControls.current
    var showAll by rememberSaveable(task.id + ":transcript") { mutableStateOf(false) }
    val captions = LocalCallCaptions.current[task.id].orEmpty()
    val lines = remember(task.transcript, captions) { transcriptLines(task.transcript, captions) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (task.live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CallSummary(task)
            if (task.brief.goal.isNotBlank()) DetailLine("Goal: ${task.brief.goal}")
            CallStatusLine(task)
            questions.filter { it.waiting || it.resolution == QuestionResolution.SUBMITTING }.forEach { question ->
                key(
                    question.questionId,
                ) { PendingCallQuestion(question, question.questionId == controls.currentQuestionId, controls.onAnswer) }
            }
            task.pendingCommitment?.let { DetailLine("Wants to agree to “$it”; waiting for approval.", MaterialTheme.colorScheme.tertiary) }
            if (task.acceptedCommitments.isNotEmpty()) {
                DetailLine(
                    "You approved stating: ${task.acceptedCommitments.joinToString {
                        "“$it”"
                    }}. Whether the other party accepted is not verified.",
                )
            }
            if (lines.isNotEmpty()) {
                HorizontalDivider()
                val shown = if (showAll) lines else lines.takeLast(COLLAPSED_LINES)
                shown.forEach { line -> key(line.id) { TranscriptLine(line) } }
                if (lines.size > COLLAPSED_LINES) {
                    Surface(onClick = { showAll = !showAll }, color = Color.Transparent) {
                        Text(
                            text = if (showAll) "Show recent lines" else "Show all ${lines.size} lines",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        )
                    }
                }
            } else if (task.live) {
                DetailLine("Nothing has been said yet.")
            }
            questions.filter { !it.waiting && it.resolution != QuestionResolution.SUBMITTING }.forEach { question ->
                key(question.questionId) { ResolvedCallQuestion(question) }
            }
            CallOutcome(task)
            if (task.hangUpOffered) CallControls(task, controls)
        }
    }
}

/** The number, the task state, and the leg with its elapsed time; also used by Running work. */
@Composable
internal fun CallSummary(task: CallTaskSnapshot) {
    val connectedSince = (task.legState as? LegState.Connected)?.sinceMillis
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(connectedSince, task.legState is LegState.Ended) {
        while (connectedSince != null && task.legState !is LegState.Ended) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val state = task.state.presentation()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Call, contentDescription = null, modifier = Modifier.size(18.dp), tint = state.color())
            Text(
                text = "Call · ${formatNumber(task.brief.number)}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f, fill = false),
            )
            StatusIndicator(state)
            Text(state.label, style = MaterialTheme.typography.labelMedium, color = state.color())
        }
        Text(
            text = legLabel(task.legState, connectedSince?.let { now - it }),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Status, not controls: one line, so nothing here looks tappable. */
@Composable
private fun CallStatusLine(task: CallTaskSnapshot) {
    if (!task.live) return
    Text(
        text =
            listOfNotNull(
                "WhatsApp".takeIf { task.providerId == "whatsapp" },
                "Twilio".takeIf { task.providerId == "twilio" },
                task.traits.callerId?.let { "from $it" },
                floorLabel(task.floor),
                phaseLabel(task.phase)?.let { "agent's read: $it" },
                "no keypad".takeIf { !task.traits.supportsDtmf },
                "listening in".takeIf { task.listening },
                task.traits.maxConnectedDurationMillis?.let { "provider limit ${formatDuration(it)}" },
            ).joinToString(" · "),
        style = MaterialTheme.typography.labelLarge,
        color = if (task.floor == CallFloor.OWNER) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun PendingCallQuestion(
    question: QuestionEvidence,
    current: Boolean,
    onAnswer: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("The agent is asking you", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Text(question.question, style = MaterialTheme.typography.bodyMedium)
            when {
                question.resolution == QuestionResolution.SUBMITTING -> {
                    Text("Sending your answer…", style = MaterialTheme.typography.labelMedium)
                }

                current -> {
                    CallButton("Answer", onAnswer)
                }

                else -> {
                    Text("Answer the earlier question first.", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun ResolvedCallQuestion(question: QuestionEvidence) {
    DetailLine(
        "Asked you: ${question.question}" +
            when (question.resolution) {
                QuestionResolution.ACCEPTED -> " — you answered “${question.answer.orEmpty()}”"
                QuestionResolution.FAILED -> " — your answer could not be delivered"
                QuestionResolution.CANCELLED -> " — cancelled"
                QuestionResolution.INTERRUPTED -> " — interrupted before an answer"
                QuestionResolution.PENDING, QuestionResolution.SUBMITTING -> ""
            },
    )
}

private class Line(
    val id: String,
    val speaker: String,
    val text: String,
    val kind: LineKind,
    val progress: LineProgress = LineProgress.FINAL,
)

/** FINAL lines are journaled transcript items; the others are live captions (see [LiveCaption]). */
private enum class LineProgress { FINAL, SPEAKING, CUT_OFF }

/**
 * Journaled items and live captions in the order they started. A caption whose finalized item has
 * arrived is dropped here too, in case the card recomposes between the two updates.
 */
private fun transcriptLines(
    transcript: List<CallTranscriptItem>,
    captions: List<LiveCaption>,
): List<Line> {
    val final = transcript.mapNotNull { item -> describe(item)?.let { item.createdAtMillis to it } }
    val finalIds = transcript.mapTo(mutableSetOf()) { it.id }
    val live =
        captions.filter { it.id !in finalIds && it.text.isNotBlank() }.map { caption ->
            val (speaker, kind) = if (caption.speaker == CallSpeaker.AGENT) "Agent" to LineKind.AGENT else "Them" to LineKind.REMOTE
            caption.startedAtMillis to
                Line(caption.id, speaker, caption.text, kind, if (caption.cutOff) LineProgress.CUT_OFF else LineProgress.SPEAKING)
        }
    return (final + live).sortedBy { it.first }.map { it.second }
}

private enum class LineKind { REMOTE, AGENT, OWNER, PRIVATE, SYSTEM }

@Composable
private fun TranscriptLine(line: Line) {
    val color =
        when (line.kind) {
            LineKind.REMOTE -> MaterialTheme.colorScheme.onSurface
            LineKind.AGENT -> MaterialTheme.colorScheme.primary
            LineKind.OWNER -> MaterialTheme.colorScheme.secondary
            LineKind.PRIVATE -> MaterialTheme.colorScheme.tertiary
            LineKind.SYSTEM -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    val suffix =
        when (line.progress) {
            LineProgress.FINAL -> ""
            LineProgress.SPEAKING -> " …"
            LineProgress.CUT_OFF -> " — cut off"
        }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "${line.speaker}: ${line.text}$suffix" },
    ) {
        Text(
            text = line.speaker,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = color,
            modifier = Modifier.width(76.dp).padding(top = 2.dp),
        )
        Text(
            text =
                buildAnnotatedString {
                    append(line.text)
                    if (line.progress == LineProgress.CUT_OFF) {
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.error, fontStyle = FontStyle.Normal)) { append(suffix) }
                    } else {
                        append(suffix)
                    }
                },
            style = if (line.kind == LineKind.SYSTEM) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            // Provisional words are set in italics so they read as still changing (or never finished).
            fontStyle = if (line.progress == LineProgress.FINAL) FontStyle.Normal else FontStyle.Italic,
            color = if (line.kind == LineKind.SYSTEM) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Speaker attribution for display. Keypad credentials are stored as their reference, never their
 * digits, and core scrubs any echo of a value before journaling, so nothing here can reveal one.
 */
private fun describe(item: CallTranscriptItem): Line? =
    when (item.speaker) {
        CallSpeaker.REMOTE -> {
            Line(item.id, "Them", item.text, LineKind.REMOTE)
        }

        CallSpeaker.AGENT -> {
            Line(item.id, "Agent", item.text, LineKind.AGENT)
        }

        CallSpeaker.OWNER_TO_REMOTE -> {
            Line(item.id, "You", item.text, LineKind.OWNER)
        }

        CallSpeaker.OWNER_TO_AGENT -> {
            Line(item.id, if (item.id.startsWith("answer:")) "Your answer" else "Whisper", item.text, LineKind.PRIVATE)
        }

        CallSpeaker.DTMF -> {
            Line(
                item.id,
                "Keypad",
                if (item.text.startsWith("credential ")) {
                    "Entered saved ${item.text} (digits hidden)"
                } else {
                    "Pressed ${item.text}"
                },
                LineKind.SYSTEM,
            )
        }

        CallSpeaker.SYSTEM_IVR -> {
            systemLine(item)
        }
    }

private fun systemLine(item: CallTranscriptItem): Line {
    val json = runCatching { Json.parseToJsonElement(item.text) as? JsonObject }.getOrNull()

    fun text(key: String) = (json?.get(key) as? JsonPrimitive)?.contentOrNull
    val tool = text("tool")
    return when {
        tool != null -> {
            val name = tool.removePrefix("eva.call.agent.")
            val status = text("status")
            Line(
                item.id,
                "Agent did",
                "${toolLabel(name)}: ${text("message").orEmpty()}" + if (status != null && status != "COMPLETED") " ($status)" else "",
                LineKind.SYSTEM,
            )
        }

        text("brainConnection") != null -> {
            Line(
                item.id,
                "EVA",
                "The agent's connection dropped (${text("detail").orEmpty()}); EVA reconnects it without redialing.",
                LineKind.SYSTEM,
            )
        }

        text("transcription") == "unavailable" -> {
            Line(item.id, "EVA", "Could not transcribe what they said.", LineKind.SYSTEM)
        }

        else -> {
            Line(item.id, "EVA", item.text, LineKind.SYSTEM)
        }
    }
}

private fun toolLabel(name: String) =
    when (name) {
        "dtmf" -> "Keypad"
        "ask_owner" -> "Asked you"
        "commit" -> "Commitment"
        "report_phase" -> "Phase"
        "hang_up" -> "Hang up"
        "finish" -> "Outcome claim"
        else -> name
    }

/** The agent's outcome is always presented as its claim; a proposal made before the leg ends says so. */
@Composable
private fun CallOutcome(task: CallTaskSnapshot) {
    val proposed = !task.state.terminal
    val outcome = if (proposed) task.proposedOutcome ?: return else task.state

    fun title(text: String) = if (proposed) "Agent's proposed outcome, call still open: $text" else text
    when (outcome) {
        is CallTaskState.Accomplished -> {
            OutcomeBox(title("agent's claim of success"), outcome.claim) {
                if (outcome.evidence.isNotEmpty()) DetailLine("Cited lines:")
                outcome.evidence.forEach { ref ->
                    val cited = task.transcript.firstOrNull { it.id == ref }?.let(::describe)
                    DetailLine(cited?.let { "${it.speaker}: “${it.text}”" } ?: "A line not in this transcript ($ref)")
                }
                DetailLine("This is the agent's claim backed by the cited lines; EVA did not verify it independently.")
            }
        }

        is CallTaskState.NotAccomplished -> {
            OutcomeBox(title("not done"), outcome.reason)
        }

        is CallTaskState.Uncertain -> {
            OutcomeBox(title("outcome uncertain"), outcome.unknown)
        }

        is CallTaskState.Interrupted -> {
            OutcomeBox(title("interrupted"), outcome.reason) { outcome.outcome?.let { DetailLine("Still unknown: ${it.unknown}") } }
        }

        else -> {
            Unit
        }
    }
}

@Composable
private fun OutcomeBox(
    title: String,
    text: String,
    extra: @Composable () -> Unit = {},
) {
    HorizontalDivider()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(text, style = MaterialTheme.typography.bodyMedium)
        extra()
    }
}

/**
 * One button family on an even grid: every control is the same outlined pill, toggles name what
 * tapping does next, and only Hang up is filled.
 */
@Composable
private fun CallControls(
    task: CallTaskSnapshot,
    controls: CallCardControls,
) {
    fun control(
        capability: String,
        vararg arguments: Pair<String, String>,
    ) = controls.onControl(task.id, capability, arguments.toMap())
    val whispering = controls.whisperingTo == task.id
    val actions =
        buildList<Pair<String, () -> Unit>> {
            if (task.live) {
                if (task.floor == CallFloor.OWNER || task.floor == CallFloor.MUTED) {
                    add("Hand back" to { control(CallCapabilities.FLOOR, "floor" to CallFloor.AGENT.name) })
                }
                if (task.floor != CallFloor.OWNER) add("Take over" to { control(CallCapabilities.FLOOR, "floor" to CallFloor.OWNER.name) })
                add(
                    (if (task.listening) "Stop listening" else "Listen in") to
                        { control(CallCapabilities.LISTEN, "enabled" to (!task.listening).toString()) },
                )
                add((if (whispering) "Stop whispering" else "Whisper") to { controls.onWhisper(if (whispering) null else task.id) })
            }
        }
    // The card sits in the action rail, which measures intrinsics, so the grid cannot subcompose.
    val windowWidth =
        with(LocalDensity.current) {
            LocalWindowInfo.current.containerSize.width
                .toDp()
        }
    val columns = if (windowWidth < 600.dp) 2 else 4
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = columns,
    ) {
        actions.forEach { (label, onClick) ->
            CallButton(label, onClick, Modifier.weight(1f))
        }
        CallButton("Hang up", { control(CallCapabilities.CANCEL) }, Modifier.weight(1f), destructive = true)
        // Keeps a short last row on the grid instead of stretching its buttons.
        repeat((columns - (actions.size + 1) % columns) % columns) { Spacer(Modifier.weight(1f)) }
    }
    // The cost of listening in, stated where it is turned on: the echo gate (CallMonitorPlayback)
    // silences the agent's input while its voice plays here, so the other party cannot interrupt it.
    // Only routes whose captured call audio can contain EVA's own playback (cellular, WhatsApp's
    // redirection) are gated. A Twilio call's audio comes straight from the SDK, so what plays on the
    // phone never reaches the agent and nothing is gated.
    if (task.listening && task.live && task.providerId != TwilioTransport.ID) {
        DetailLine(
            "While the agent's voice plays here, it can't hear them as it speaks, so they can't interrupt it. Stop listening to let them.",
        )
    }
    if (task.live) {
        DetailLine(
            when (task.floor) {
                CallFloor.OWNER -> "Your phone's microphone is open to them. Hand back when you want the agent to continue."
                else -> "Your phone's microphone is muted to them while the agent has the call."
            },
        )
    }
}

@Composable
internal fun CallButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
) {
    val sized = modifier.heightIn(min = 40.dp)
    if (destructive) {
        Button(
            onClick = onClick,
            modifier = sized,
            colors = ButtonDefaults.buttonColors(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onError),
        ) { Text(label, maxLines = 1) }
    } else {
        OutlinedButton(onClick = onClick, modifier = sized) { Text(label, maxLines = 1) }
    }
}

@Composable
private fun DetailLine(
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
}

private fun CallTaskState.presentation(): StatusPresentation =
    when (this) {
        CallTaskState.Briefing -> {
            StatusPresentation("Preparing", inProgress = true) { MaterialTheme.colorScheme.tertiary }
        }

        CallTaskState.Dialing -> {
            StatusPresentation("Dialing", inProgress = true) { MaterialTheme.colorScheme.tertiary }
        }

        CallTaskState.InCall -> {
            StatusPresentation("In call", inProgress = true) { MaterialTheme.colorScheme.primary }
        }

        CallTaskState.WrappingUp -> {
            StatusPresentation("Wrapping up", inProgress = true) { MaterialTheme.colorScheme.primary }
        }

        is CallTaskState.Accomplished -> {
            StatusPresentation("Agent reports done", inProgress = false) { MaterialTheme.colorScheme.primary }
        }

        is CallTaskState.NotAccomplished -> {
            StatusPresentation(
                "Not done",
                inProgress = false,
            ) { MaterialTheme.colorScheme.onSurfaceVariant }
        }

        is CallTaskState.Uncertain -> {
            StatusPresentation("Outcome uncertain", inProgress = false) { MaterialTheme.colorScheme.error }
        }

        is CallTaskState.Interrupted -> {
            StatusPresentation("Interrupted", inProgress = false) { MaterialTheme.colorScheme.onSurfaceVariant }
        }
    }

private fun legLabel(
    leg: LegState,
    elapsedMillis: Long?,
): String =
    when (leg) {
        LegState.Placing -> {
            "Placing the call…"
        }

        LegState.Ringing -> {
            "Ringing…"
        }

        is LegState.Connected -> {
            "Connected · ${formatElapsed(elapsedMillis ?: 0)}"
        }

        is LegState.Ended -> {
            "Call ended: " +
                when (leg.cause) {
                    EndCause.LOCAL_HANGUP -> "hung up from this phone"
                    EndCause.REMOTE_HANGUP -> "they hung up"
                    EndCause.BUSY -> "busy"
                    EndCause.NO_ANSWER -> "no answer"
                    EndCause.FAILED -> "the call failed"
                    EndCause.DROPPED -> "the call dropped"
                } + leg.detail?.let { " ($it)" }.orEmpty()
        }
    }

private fun floorLabel(floor: CallFloor) =
    when (floor) {
        CallFloor.AGENT -> "Agent is speaking for you"
        CallFloor.OWNER -> "You have the floor"
        CallFloor.AGENT_STALLING -> "Agent is waiting for your answer"
        CallFloor.MUTED -> "Muted: nobody speaks for you"
    }

private fun phaseLabel(phase: CallPhase) =
    when (phase) {
        CallPhase.IVR -> "phone menu"
        CallPhase.HOLD -> "on hold"
        CallPhase.HUMAN -> "talking to a person"
        CallPhase.TRANSFERRING -> "being transferred"
        CallPhase.VERIFYING -> "confirming details"
        CallPhase.UNKNOWN -> null
    }

private fun formatNumber(number: String) = PhoneNumberUtils.formatNumber(number, Locale.getDefault().country) ?: number

private fun formatElapsed(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1_000
    return if (seconds >= 3_600) {
        "%d:%02d:%02d".format(seconds / 3_600, seconds / 60 % 60, seconds % 60)
    } else {
        "%d:%02d".format(seconds / 60, seconds % 60)
    }
}

private fun formatDuration(millis: Long): String {
    val minutes = millis / 60_000
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
}

private const val COLLAPSED_LINES = 6
