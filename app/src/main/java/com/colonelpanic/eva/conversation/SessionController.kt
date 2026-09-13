package com.colonelpanic.eva.conversation

import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.InvocationPersistenceException
import com.colonelpanic.eva.capability.InvocationRecord
import com.colonelpanic.eva.capability.InvocationRepository
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.ProposalRejectedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** Submit and the supplied scope must run on the same serialized UI dispatcher. */
class SessionController(
    private val provider: TypedInputProvider,
    private val dispatcher: CapabilityDispatcher,
    private val repository: InvocationRepository,
    private val scope: CoroutineScope,
    private val newCallId: () -> String = { "local:${UUID.randomUUID()}" },
) {
    private val mutableState = MutableStateFlow(ConversationState())
    val state = mutableState.asStateFlow()

    init {
        scope.launch {
            try {
                repository.recoverInterrupted()
                mutableState.value = ConversationState(entries = repository.history().map { it.toEntry() }, isLoading = false)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.value = ConversationState(isLoading = false, errorMessage = STORAGE_ERROR)
            }
        }
    }

    fun submit(input: String) {
        val current = mutableState.value
        if (current.isLoading || current.isSubmitting || current.errorMessage != null || input.isBlank()) return
        val id = newCallId()
        if (input.length > 1000) {
            append(ConversationEntry(id, input.take(1000), "Keep requests under 1,000 characters.", EntryStatus.NOT_EXECUTED))
            return
        }
        val proposal = provider.propose(id, input)
        if (proposal == null) {
            append(
                ConversationEntry(
                    id,
                    input,
                    "Try ‘map <place>’, ‘navigate to <place>’, or ‘text <number>: <message>’.",
                    EntryStatus.NOT_EXECUTED,
                ),
            )
            return
        }
        mutableState.update {
            it.copy(
                entries =
                    (
                        it.entries +
                            ConversationEntry(
                                id,
                                input,
                                "Preparing action…",
                                EntryStatus.PENDING,
                                proposal.arguments["destination"],
                                proposal.capabilityId,
                            )
                    ).takeLast(100),
                isSubmitting = true,
            )
        }
        scope.launch {
            try {
                val result = dispatcher.execute(proposal)
                mutableState.update { it.copy(entries = it.entries.map { entry -> if (entry.id == id) result.toEntry() else entry }) }
            } catch (error: ProposalRejectedException) {
                finishEntry(id, EntryStatus.NOT_EXECUTED, checkNotNull(error.message))
            } catch (error: InvocationPersistenceException) {
                val status = if (error.mayHaveExecuted) EntryStatus.UNKNOWN else EntryStatus.NOT_EXECUTED
                val message =
                    if (error.mayHaveExecuted) {
                        CapabilityDispatcher.UNKNOWN_MESSAGE
                    } else {
                        "Action history could not be saved. No new app was opened."
                    }
                finishEntry(id, status, message, storageFailed = true)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                finishEntry(id, EntryStatus.UNKNOWN, CapabilityDispatcher.UNKNOWN_MESSAGE, storageFailed = true)
            } finally {
                mutableState.update { it.copy(isSubmitting = false) }
            }
        }
    }

    private fun finishEntry(
        id: String,
        status: EntryStatus,
        response: String,
        storageFailed: Boolean = false,
    ) {
        mutableState.update { current ->
            current.copy(
                entries = current.entries.map { if (it.id == id) it.copy(status = status, response = response) else it },
                errorMessage = if (storageFailed) STORAGE_ERROR else current.errorMessage,
            )
        }
    }

    private fun append(entry: ConversationEntry) {
        mutableState.update { it.copy(entries = (it.entries + entry).takeLast(100)) }
    }

    private fun InvocationRecord.toEntry() =
        ConversationEntry(
            id = callId,
            request = request,
            response = message,
            status =
                when (status) {
                    InvocationStatus.CLAIMED -> EntryStatus.PENDING
                    InvocationStatus.DISPATCHING -> EntryStatus.DISPATCHING
                    InvocationStatus.HANDED_OFF -> EntryStatus.HANDED_OFF
                    InvocationStatus.NOT_EXECUTED -> EntryStatus.NOT_EXECUTED
                    InvocationStatus.FAILED -> EntryStatus.FAILED
                    InvocationStatus.UNKNOWN -> EntryStatus.UNKNOWN
                },
            destination = destination,
            capabilityId = capabilityId,
        )

    companion object {
        const val STORAGE_ERROR =
            "EVA could not safely read or save action history. Restart EVA before sending another request; " +
                "check the other app if an action was in progress."
    }
}
