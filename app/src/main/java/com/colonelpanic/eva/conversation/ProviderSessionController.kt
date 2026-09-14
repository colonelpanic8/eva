package com.colonelpanic.eva.conversation

import com.colonelpanic.eva.audio.RealtimeMediaSession
import com.colonelpanic.eva.audio.RealtimeMediaState
import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.InvocationPersistenceException
import com.colonelpanic.eva.capability.InvocationRecord
import com.colonelpanic.eva.capability.InvocationRepository
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.ProposalRejectedException
import com.colonelpanic.eva.capability.ToolProposal
import com.colonelpanic.eva.capability.ToolSchema
import com.colonelpanic.eva.providers.ConversationInput
import com.colonelpanic.eva.providers.ConversationProvider
import com.colonelpanic.eva.providers.ConversationSession
import com.colonelpanic.eva.providers.CorrelatedToolResult
import com.colonelpanic.eva.providers.ProviderEvent
import com.colonelpanic.eva.providers.ProviderToolCatalog
import com.colonelpanic.eva.providers.ProviderToolDefinition
import com.colonelpanic.eva.providers.ResponseRequest
import com.colonelpanic.eva.providers.SessionOpenRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest
import java.util.UUID

/** Calls and the supplied scope are confined to the UI dispatcher. */
class ProviderSessionController(
    private val registry: CapabilityRegistry,
    private val dispatcher: CapabilityDispatcher,
    private val repository: InvocationRepository,
    private val scope: CoroutineScope,
    private val providerFactory: (String) -> ConversationProvider,
    private val mediaFactory: (() -> RealtimeMediaSession)? = null,
    private val voiceProviderFactory: (suspend (String, RealtimeMediaSession) -> ConversationProvider)? = null,
    private val voiceLookupRetries: () -> Int = { 5 },
    private val voiceKeywords: suspend () -> List<String> = { emptyList() },
) {
    private val mutableState = MutableStateFlow(ConversationState())
    val state = mutableState.asStateFlow()
    private var media: RealtimeMediaSession? = null
    private var connectionJob: Job? = null
    private val actionJobs = mutableSetOf<Job>()
    private var session: ConversationSession? = null
    private var attempt = 0
    private var currentInput: ConversationInput? = null
    private var claimedCall: String? = null
    private var lastUserTranscript: String? = null
    private val definitions = registry.catalog.associateBy { it.id }
    private val phoneTools = registry.catalog.map { ProviderToolDefinition(it.id, it.title, it.description, it.inputSchema) }
    private val typedCatalog = catalogOf(phoneTools)

    // Only a spoken session is something the model can hang up.
    private val voiceCatalog = catalogOf(phoneTools + END_CONVERSATION)
    private var ending = false
    private var assistantSpeaking = false

    init {
        scope.launch {
            try {
                repository.recoverInterrupted()
                val history = repository.history().map { record -> record.entry() }
                mutableState.update { it.copy(entries = history, isLoading = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update { it.copy(isLoading = false, errorMessage = SessionController.STORAGE_ERROR) }
            }
        }
    }

    fun connect(link: String) = connectSession(link, voice = false)

    fun connectVoice(link: String) = connectSession(link, voice = true)

    fun toggleMicrophone() {
        media?.let { it.setMicrophoneMuted(!it.controls.value.microphoneMuted) }
    }

    fun togglePlayback() {
        media?.let { it.setPlaybackMuted(!it.controls.value.playbackMuted) }
    }

    private fun connectSession(
        link: String,
        voice: Boolean,
    ) {
        if (state.value.isLoading || state.value.errorMessage != null) return
        disconnect()
        val thisAttempt = attempt
        mutableState.update { it.copy(providerStatus = ProviderStatus.CONNECTING, providerMessage = null, voiceMode = voice) }
        connectionJob =
            scope.launch {
                var openedSession: ConversationSession? = null
                try {
                    val connectionCatalog = if (voice) voiceCatalog else typedCatalog
                    val provider =
                        if (!voice) {
                            providerFactory(link)
                        } else {
                            val audio = checkNotNull(mediaFactory).invoke()
                            media = audio
                            launch {
                                audio.controls.collect { controls ->
                                    if (thisAttempt == attempt) mutableState.update { it.copy(mediaControls = controls) }
                                }
                            }
                            launch {
                                audio.state.collect { audioState ->
                                    if (thisAttempt == attempt) {
                                        mutableState.update { it.copy(mediaState = audioState) }
                                        if (audioState is RealtimeMediaState.Failed) {
                                            mutableState.update { it.copy(providerMessage = audioState.reason.message) }
                                            disconnect()
                                        }
                                    }
                                }
                            }
                            checkNotNull(voiceProviderFactory).invoke(link, audio)
                        }
                    currentCoroutineContext().ensureActive()
                    val opened =
                        provider.open(
                            SessionOpenRequest(
                                if (!voice) {
                                    "You are EVA, an assistant running on the user's Android phone. " +
                                        "Help conversationally and use the supplied tools for phone actions. " +
                                        "Ask for missing information. Never claim sending a message when only a draft was opened. " +
                                        clock()
                                } else {
                                    "You are EVA, a voice assistant running on the user's Android phone. " +
                                        "Keep spoken replies short. Use the supplied tools for phone actions and say " +
                                        "what the tool result reports. Never claim sending a message when only a draft was opened. " +
                                        "Spoken names may be transcribed with the wrong spelling. For read-only lookups such as " +
                                        "contacts search, first assess how ambiguous the name you heard is. If it could reasonably " +
                                        "have multiple spellings, generate and rank the plausible spellings and phonetic variants, " +
                                        "deduplicate them, and proactively search the most likely variants. After the initial lookup, " +
                                        "make up to ${voiceLookupRetries()} additional lookup queries in total. Use that budget for " +
                                        "the best spelling variants and, when a full name does not find a clear match, the first name " +
                                        "or last name by itself. Do not spend queries on implausible variations. " +
                                        "Use only query forms supported by the tool; do not put several alternatives into one query " +
                                        "unless the tool supports it. Respect spellings explicitly supplied by the user. " +
                                        "Use returned records to identify matches; never invent a person or contact detail. " +
                                        "If different people plausibly match, ask which one the user means before acting. " +
                                        "If these lookups still find nothing, ask for the spelling or another identifying detail. " +
                                        "Apply these retries only to read-only lookups, never to sending, calling, or opening apps. " +
                                        "When the user is finished, because they say goodbye, say that is all, or ask you to " +
                                        "hang up, say a brief goodbye and then end the conversation with its tool. " +
                                        clock()
                                },
                                connectionCatalog,
                                // Captions only; a typed session has no audio to transcribe.
                                if (voice) voiceKeywords() else emptyList(),
                            ),
                        )
                    openedSession = opened
                    currentCoroutineContext().ensureActive()
                    session = opened
                    opened.events.takeWhile { it != ProviderEvent.Closed }.collect { event ->
                        if (thisAttempt != attempt) return@collect
                        when (event) {
                            is ProviderEvent.Connected -> {
                                check(event.catalogRevision == connectionCatalog.revision)
                                mutableState.update {
                                    it.copy(
                                        providerStatus = ProviderStatus.CONNECTED,
                                        providerMessage = null,
                                        providerModel =
                                            listOfNotNull(event.model, event.backendModel)
                                                .distinct()
                                                .joinToString(" · ")
                                                .ifBlank { null },
                                    )
                                }
                                append(
                                    ConversationEntry("boundary:${opened.connectionEpoch}", "", "New model session", EntryStatus.SESSION),
                                )
                            }

                            is ProviderEvent.Account -> {
                                mutableState.update { it.copy(providerLabel = event.label) }
                            }

                            is ProviderEvent.AssistantText -> {
                                if (event.inputId == currentInput?.id) {
                                    val text = event.text + if (event.truncated) "\n[Response was truncated]" else ""
                                    mutableState.update {
                                        it.copy(
                                            entries =
                                                it.entries.map { entry ->
                                                    if (entry.id ==
                                                        event.inputId
                                                    ) {
                                                        entry.copy(
                                                            response =
                                                                listOf(
                                                                    entry.response
                                                                        .takeUnless {
                                                                            entry.status ==
                                                                                EntryStatus.PENDING
                                                                        }.orEmpty(),
                                                                    text,
                                                                ).filter { part -> part.isNotEmpty() }
                                                                    .joinToString("\n"),
                                                            status = EntryStatus.ANSWER,
                                                        )
                                                    } else {
                                                        entry
                                                    }
                                                },
                                        )
                                    }
                                }
                            }

                            is ProviderEvent.ToolCallReady -> {
                                check(event.call.catalogRevision == connectionCatalog.revision)
                                if (voice && event.capabilityId == END_CONVERSATION.capabilityId) {
                                    // Nothing runs on the phone and no result is returned, so the model
                                    // is not prompted to speak again. Its goodbye plays out first.
                                    ending = true
                                    launch {
                                        if (assistantSpeaking) delay(END_SPEECH_LIMIT_MILLIS)
                                        hangUp()
                                    }
                                } else {
                                    dispatch(event, opened, thisAttempt)
                                }
                            }

                            is ProviderEvent.AssistantSpeaking -> {
                                assistantSpeaking = event.speaking
                                if (ending && !event.speaking) {
                                    launch {
                                        // The phone still holds a little audio after the server drains.
                                        delay(PLAYOUT_TAIL_MILLIS)
                                        hangUp()
                                    }
                                }
                            }

                            is ProviderEvent.ResponseEnded -> {
                                check(event.inputId == currentInput?.id)
                                finishInput(
                                    when (event.status) {
                                        "completed" -> "Response completed."
                                        "cancelled" -> "Interrupted."
                                        else -> "The model could not complete this response."
                                    },
                                )
                            }

                            is ProviderEvent.Failure -> {
                                throw IllegalStateException(event.message)
                            }

                            is ProviderEvent.ResponseStarted -> {
                                if (currentInput == null) {
                                    // Voice: the delegated turn is the input. Its transcript may
                                    // still be in flight, so the request text is resolved at dispatch.
                                    val input = ConversationInput(event.inputId, "")
                                    currentInput = input
                                    claimedCall = null
                                    append(ConversationEntry(input.id, "", "Working on it…", EntryStatus.PENDING))
                                }
                            }

                            ProviderEvent.Closed -> {}

                            is ProviderEvent.Transcript -> {
                                if (event.role == "user") lastUserTranscript = event.text
                                append(
                                    ConversationEntry(
                                        UUID.randomUUID().toString(),
                                        if (event.role ==
                                            "user"
                                        ) {
                                            event.text
                                        } else {
                                            ""
                                        },
                                        if (event.role == "assistant") event.text else "",
                                        EntryStatus.ANSWER,
                                    ),
                                )
                            }
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (thisAttempt ==
                        attempt
                    ) {
                        mutableState.update { it.copy(providerMessage = error.message?.take(300) ?: "Provider connection failed.") }
                    }
                } finally {
                    currentCoroutineContext().cancelChildren()
                    if (thisAttempt == attempt) {
                        actionJobs.toList().forEach { it.cancel() }
                        session = null
                        media?.close()
                        media = null
                        finishInput("The connection ended before the response completed.")
                        mutableState.update {
                            it.copy(
                                providerStatus = ProviderStatus.DISCONNECTED,
                                providerModel = null,
                                voiceMode = false,
                                mediaState = if (it.voiceMode) RealtimeMediaState.Closed else it.mediaState,
                            )
                        }
                    }
                    withContext(NonCancellable) {
                        try {
                            openedSession?.close()
                        } catch (_: Exception) {
                            if (thisAttempt == attempt) {
                                mutableState.update {
                                    it.copy(providerMessage = it.providerMessage ?: "Provider cleanup failed. Reconnect to try again.")
                                }
                            }
                        }
                    }
                }
            }
    }

    fun disconnect() {
        attempt++
        ending = false
        assistantSpeaking = false
        connectionJob?.cancel()
        media?.close()
        media = null
        actionJobs.toList().forEach { it.cancel() }
        session = null
        finishInput("Disconnected before the response completed.")
        mutableState.update {
            it.copy(
                providerStatus = ProviderStatus.DISCONNECTED,
                providerModel = null,
                voiceMode = false,
                mediaState = if (it.voiceMode) RealtimeMediaState.Closed else it.mediaState,
            )
        }
    }

    fun submit(text: String) {
        val current = state.value
        val opened = session ?: return
        if (current.isLoading || current.isSubmitting || current.errorMessage != null ||
            current.providerStatus != ProviderStatus.CONNECTED || current.voiceMode ||
            text.isBlank()
        ) {
            return
        }
        if (text.length > 1000) {
            append(
                ConversationEntry(
                    UUID.randomUUID().toString(),
                    text.take(1000),
                    "Keep requests under 1,000 characters.",
                    EntryStatus.NOT_EXECUTED,
                ),
            )
            return
        }
        val input = ConversationInput(UUID.randomUUID().toString(), text)
        currentInput = input
        claimedCall = null
        append(ConversationEntry(input.id, text, "Thinking…", EntryStatus.PENDING))
        mutableState.update { it.copy(isSubmitting = true) }
        scope.launch {
            try {
                opened.submit(input)
                opened.requestResponse(ResponseRequest(input.id))
            } catch (
                error: CancellationException,
            ) {
                throw error
            } catch (_: Exception) {
                if (session === opened) {
                    disconnect()
                    mutableState.update { it.copy(providerMessage = "Could not submit this request. Reconnect to try again.") }
                }
            }
        }
    }

    private fun dispatch(
        event: ProviderEvent.ToolCallReady,
        opened: ConversationSession,
        thisAttempt: Int,
    ) {
        val input = currentInput ?: error("Tool call arrived without a user input")
        check(event.call.connectionEpoch == opened.connectionEpoch && event.call.inputId == input.id)
        val definition = checkNotNull(definitions[event.capabilityId])
        val rejection =
            when {
                claimedCall != null && claimedCall != event.call.callId -> "One phone action is permitted per request."
                else -> ToolSchema.error(definition.inputSchema, event.arguments)
            }
        if (claimedCall == null) claimedCall = event.call.callId
        val arguments = event.arguments.mapValues { (_, value) -> (value as? JsonPrimitive)?.content }
        val argumentError = if (arguments.values.any { it == null }) "This action binding requires scalar arguments." else null
        val id = "provider:${event.call.providerSessionId}:${event.call.callId}"
        val request = input.text.ifBlank { lastUserTranscript ?: VOICE_REQUEST }
        val proposal = ToolProposal(id, event.capabilityId, arguments.mapValues { it.value.orEmpty() }, request)
        append(ConversationEntry(id, "", "Preparing action…", EntryStatus.PENDING, actionTitle = definition.title))
        val job =
            scope.launch {
                try {
                    val result = dispatcher.execute(proposal, rejection ?: argumentError)
                    upsert(result.entry().copy(request = "", actionTitle = definition.title))
                    if (attempt == thisAttempt &&
                        session === opened
                    ) {
                        opened.submitToolResult(CorrelatedToolResult(event.call, result.status.name, result.message))
                    }
                } catch (error: ProposalRejectedException) {
                    upsert(ConversationEntry(id, "", error.message.orEmpty(), EntryStatus.NOT_EXECUTED, actionTitle = definition.title))
                    if (attempt == thisAttempt && session === opened) {
                        try {
                            opened.submitToolResult(CorrelatedToolResult(event.call, "NOT_EXECUTED", error.message.orEmpty()))
                        } catch (canceled: CancellationException) {
                            throw canceled
                        } catch (_: Exception) {
                            if (attempt == thisAttempt) disconnect()
                        }
                    }
                } catch (error: InvocationPersistenceException) {
                    upsert(
                        ConversationEntry(
                            id,
                            "",
                            if (error.mayHaveExecuted) {
                                CapabilityDispatcher.UNKNOWN_MESSAGE
                            } else {
                                "Action history could not be saved. Nothing was executed."
                            },
                            if (error.mayHaveExecuted) EntryStatus.UNKNOWN else EntryStatus.NOT_EXECUTED,
                            actionTitle = definition.title,
                        ),
                    )
                    mutableState.update { it.copy(errorMessage = SessionController.STORAGE_ERROR) }
                    disconnect()
                } catch (error: CancellationException) {
                    withContext(NonCancellable) {
                        repository.history().find { it.callId == id }?.let {
                            upsert(
                                it.entry().copy(request = "", actionTitle = definition.title),
                            )
                        }
                    }
                    throw error
                } catch (_: Exception) {
                    if (attempt == thisAttempt && session === opened) {
                        disconnect()
                        mutableState.update {
                            it.copy(
                                providerMessage = "The tool exchange ended. Check the action receipt before retrying.",
                            )
                        }
                    }
                }
            }
        actionJobs.add(job)
        job.invokeOnCompletion { actionJobs.remove(job) }
    }

    private fun hangUp() {
        finishInput("Conversation ended.")
        disconnect()
    }

    private fun finishInput(message: String) {
        val id = currentInput?.id
        currentInput = null
        lastUserTranscript = null
        mutableState.update {
            it.copy(
                isSubmitting = false,
                entries =
                    it.entries.map { entry ->
                        if (entry.id == id &&
                            entry.status == EntryStatus.PENDING
                        ) {
                            entry.copy(status = EntryStatus.ANSWER, response = message)
                        } else {
                            entry
                        }
                    },
            )
        }
    }

    private companion object {
        const val VOICE_REQUEST = "Voice request"

        /** Bounds the wait for a goodbye whose end is never reported. */
        const val END_SPEECH_LIMIT_MILLIS = 10_000L
        const val PLAYOUT_TAIL_MILLIS = 500L

        /** Hangs up rather than acting on the phone, so it bypasses the dispatcher and journal. */
        val END_CONVERSATION =
            ProviderToolDefinition(
                "eva.session.end",
                "End the conversation",
                "Hang up this voice conversation. Call it when the user says goodbye, says they are done, or asks " +
                    "you to hang up, after a brief spoken goodbye; the goodbye finishes playing before the call ends. " +
                    "Do not call it while a request is unfinished or you are waiting for the user to answer.",
                Json.parseToJsonElement("""{"type":"object","properties":{},"required":[],"additionalProperties":false}""").jsonObject,
            )

        fun catalogOf(tools: List<ProviderToolDefinition>) =
            ProviderToolCatalog(
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(tools.joinToString { "${it.capabilityId}:${it.description}:${it.inputSchema}" }.toByteArray())
                    .joinToString("") { "%02x".format(it) },
                tools,
            )
    }

    private fun clock(): String {
        val now = java.util.Calendar.getInstance()
        val format = java.text.SimpleDateFormat("EEEE yyyy-MM-dd HH:mm zzz", java.util.Locale.US)
        return "The user's current local time is ${format.format(now.time)}, " +
            "which is ${now.timeInMillis} in Unix milliseconds."
    }

    private fun append(entry: ConversationEntry) = upsert(entry)

    private fun upsert(entry: ConversationEntry) {
        mutableState.update { it.copy(entries = (it.entries.filterNot { previous -> previous.id == entry.id } + entry).takeLast(100)) }
    }

    private fun InvocationRecord.entry() =
        ConversationEntry(
            callId,
            request,
            message,
            when (status) {
                InvocationStatus.CLAIMED -> EntryStatus.PENDING
                InvocationStatus.DISPATCHING -> EntryStatus.DISPATCHING
                InvocationStatus.HANDED_OFF -> EntryStatus.HANDED_OFF
                InvocationStatus.COMPLETED -> EntryStatus.COMPLETED
                InvocationStatus.NOT_EXECUTED -> EntryStatus.NOT_EXECUTED
                InvocationStatus.FAILED -> EntryStatus.FAILED
                InvocationStatus.UNKNOWN -> EntryStatus.UNKNOWN
            },
            destination,
            capabilityId,
            title,
        )
}
