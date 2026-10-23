package com.colonelpanic.eva.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.colonelpanic.eva.ui.ModelPicker
import kotlin.math.roundToInt

@Composable
internal fun CallAgentSection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    val tuning = state.callAgent
    SettingsSection("Phone call agent") {
        SettingsBlock {
            ModelPicker(
                "Call voice",
                tuning.voice,
                listOf("alloy", "ash", "ballad", "coral", "echo", "sage", "shimmer", "verse", "marin", "cedar"),
                "marin",
            ) { actions.onCallAgentChange(tuning.copy(voice = it.ifBlank { "marin" })) }
            SettingsNote("Applies when the next phone call agent connects.")
        }
        CallReadinessCheck(state, actions)
        SettingsSwitchRow(
            title = "WhatsApp calling (unverified)",
            supporting =
                "Experimental route for saved WhatsApp contacts. Requires Shizuku; " +
                    "a complete WhatsApp call has not yet been verified.",
            checked = tuning.whatsAppEnabled,
            onCheckedChange = { actions.onCallAgentChange(tuning.copy(whatsAppEnabled = it)) },
        )
        SettingsSwitchRow(
            title = "Listen in on new calls",
            supporting =
                "Play the agent's voice on this phone from the moment a call connects, through the earpiece, " +
                    "speaker or headset the call uses; a call's card turns it on or off for that call. While it plays, " +
                    "cellular and WhatsApp capture may suppress interruptions to prevent echo. Twilio keeps full interruption. " +
                    "The card shows the agent's words either way.",
            checked = tuning.listenInByDefault,
            onCheckedChange = { actions.onCallAgentChange(tuning.copy(listenInByDefault = it)) },
        )
        SettingsBlock {
            Text(
                "How the agent decides the other party finished speaking. A higher threshold ignores more " +
                    "line noise; a longer pause keeps sentences together but replies later. " +
                    "capabilities.callAgent in the configuration file accepts values outside these sliders.",
            )
            var threshold by remember(tuning.vadThreshold) { mutableFloatStateOf(tuning.vadThreshold.toFloat()) }
            Text("Speech threshold: ${(threshold * 100).roundToInt() / 100.0}")
            Slider(
                value = threshold,
                onValueChange = { threshold = it },
                onValueChangeFinished = {
                    actions.onCallAgentChange(tuning.copy(vadThreshold = (threshold * 100).roundToInt() / 100.0))
                },
                valueRange = 0f..1f,
                steps = 19,
            )
            var silence by remember(tuning.vadSilenceMillis) { mutableFloatStateOf(tuning.vadSilenceMillis.toFloat()) }
            Text("End of turn after: ${silence.roundToInt()} ms of silence")
            Slider(
                value = silence,
                onValueChange = { silence = it },
                onValueChangeFinished = { actions.onCallAgentChange(tuning.copy(vadSilenceMillis = silence.roundToInt())) },
                valueRange = 100f..3_000f,
                steps = 28,
            )
        }
        TwilioCallsEditor(state, actions)
    }
}

/** The NIST time announcement: always answers, needs no input, and states something the agent can report. */
private const val TEST_LINE = "+1 303-499-7111"

/** What a test call asks EVA, in the user's own words, so the whole path from request to call card is exercised. */
internal fun testCallRequest(route: String) =
    "Phone call test over the $route call route: call $TEST_LINE, the NIST time announcement. " +
        "The call agent should only listen, tell me the time it announces, and hang up."

private fun routeName(id: String) =
    when (id) {
        "cellular" -> "Cellular"
        "whatsapp" -> "WhatsApp"
        "twilio" -> "Twilio"
        else -> id
    }

/** Checks the call agent and every route, and offers a real test call over each route that can dial a number. */
@Composable
private fun CallReadinessCheck(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    val readiness = state.callReadiness
    var expanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(expanded) { if (expanded) actions.onCheckCalls() }
    val defaultRoute = readiness?.defaultRoute
    val blocked = readiness != null && (readiness.agentProblem != null || defaultRoute == null)
    ExpandableSettingsRow(
        title = "Check phone calls",
        supporting =
            when {
                state.callReadinessChecking -> "Checking…"
                readiness == null -> "Check the call agent and each route, then place a test call"
                readiness.agentProblem != null -> "The call agent cannot start"
                defaultRoute == null -> "No route can place a call"
                else -> "Ready · calls use ${routeName(defaultRoute)} unless you ask for another route"
            },
        supportingIsError = blocked && !state.callReadinessChecking,
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        SettingsBlock {
            if (readiness == null) {
                SettingsNote("Checking…")
                return@SettingsBlock
            }
            SettingsNote("Call agent: ${readiness.agentProblem ?: "ready"}", error = readiness.agentProblem != null)
            readiness.routes.forEach { route ->
                SettingsNote("${routeName(route.id)}: ${route.problem ?: "ready"}", error = route.problem != null)
            }
            val testable = readiness.routes.filter { it.problem == null && it.id != "whatsapp" }
            if (testable.isNotEmpty()) {
                Text(
                    "A test call opens a new conversation with a request to call the NIST time announcement " +
                        "($TEST_LINE); review and send it. The agent listens, reports the announced time from the " +
                        "transcript, and hangs up. Twilio bills the call.",
                )
            }
            if (readiness.routes.any { it.id == "whatsapp" && it.problem == null }) {
                Text("WhatsApp calls reach only saved contacts with WhatsApp; test it by asking EVA to call one.")
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                testable.forEach { route ->
                    Button(
                        enabled = readiness.agentProblem == null,
                        onClick = { actions.onPlaceTestCall(route.id) },
                    ) { Text("Test ${routeName(route.id)}") }
                }
                TextButton(enabled = !state.callReadinessChecking, onClick = actions.onCheckCalls) { Text("Check again") }
            }
        }
    }
}

/** The Twilio call route: identifiers that travel with the configuration, and this phone's API key. */
@Composable
private fun TwilioCallsEditor(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    val saved = state.twilioCalls
    var account by remember(saved) { mutableStateOf(saved?.accountSid.orEmpty()) }
    var app by remember(saved) { mutableStateOf(saved?.twimlAppSid.orEmpty()) }
    var callerId by remember(saved) { mutableStateOf(saved?.callerId.orEmpty()) }
    var keySid by remember(saved) { mutableStateOf("") }
    var keySecret by remember(saved) { mutableStateOf("") }
    var error by remember(saved) { mutableStateOf<String?>(null) }
    val needsKey = saved != null && !state.twilioCallsKeySaved
    var expanded by rememberSaveable { mutableStateOf(needsKey) }
    ExpandableSettingsRow(
        title = "Twilio calls",
        supporting =
            when {
                saved == null -> "Call from a Twilio number, without Shizuku"
                needsKey -> "An API key is needed on this phone"
                else -> "From ${saved.callerId} · API key saved"
            },
        supportingIsError = needsKey,
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        SettingsBlock {
            Text(
                "EVA calls through the Twilio Voice SDK and a TwiML app whose voice URL dials the To parameter from " +
                    "this caller ID. The API key stays on this phone; the rest is part of your configuration.",
            )
            OutlinedTextField(
                account,
                { account = it },
                label = { Text("Account SID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(app, { app = it }, label = { Text("TwiML app SID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                callerId,
                { callerId = it },
                label = { Text("Caller ID") },
                supportingText = { Text("A Twilio number or verified caller ID, such as +15551234567") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                keySid,
                { keySid = it },
                label = { Text(if (state.twilioCallsKeySaved) "New API key SID" else "API key SID") },
                supportingText = { if (state.twilioCallsKeySaved) Text("Blank keeps the saved key") },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                keySecret,
                { keySecret = it },
                label = { Text("API key secret") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { SettingsNote(it, error = true) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    error = actions.onSaveTwilioCalls(account, app, callerId, keySid, keySecret)
                    if (error == null) {
                        keySid = ""
                        keySecret = ""
                    }
                }) { Text("Save") }
                if (saved != null) TextButton(onClick = actions.onRemoveTwilioCalls) { Text("Remove") }
            }
        }
    }
}
