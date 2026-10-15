package com.colonelpanic.eva.wear

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID

class WatchTaskClient(
    context: Context,
    private val scope: CoroutineScope,
    private val phone: suspend () -> String,
    private val send: suspend (String, WatchTaskRequest) -> Unit = { node, request ->
        Wearable.getMessageClient(context).sendMessage(node, WatchTasks.REQUEST, WearProtocol.encode(request)).await()
    },
) {
    private val prefs = context.getSharedPreferences("eva.watch.tasks", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(WatchTaskUi(receipt = prefs.getString("receipt", "").orEmpty()))
    val state = mutable.asStateFlow()
    private val pending = mutableMapOf<String, Pair<String, WatchTaskRequest>>()
    private val assemblies = mutableMapOf<String, WatchReplyAssembly>()
    private var readId: String? = null
    private var browsingOlder = false
    private var latestSubmission: String? = null

    fun open(threadId: String?) {
        mutable.value = state.value.copy(threadId = threadId, entries = emptyList(), question = null, questionId = null, nextBefore = null)
        readId?.let {
            pending.remove(it)
            assemblies.remove(it)
        }
        readId = null
        browsingOlder = false
        refresh()
    }

    fun poll() {
        if (!browsingOlder) refresh()
    }

    fun refresh(older: Boolean = false) {
        browsingOlder = older
        if (!older && readId != null) return
        val current = state.value
        request(
            WatchTaskRequest(
                UUID.randomUUID().toString(),
                if (current.threadId ==
                    null
                ) {
                    WatchTaskOperation.LIST
                } else {
                    WatchTaskOperation.READ
                },
                threadId = current.threadId,
                before = if (older) current.nextBefore else null,
            ),
        )
    }

    fun submit(
        text: String,
        threadId: String?,
        questionId: String?,
    ) {
        if (text.isBlank()) {
            failure("No speech was recognized. Try dictating again.")
            return
        }
        val request = WatchTaskRequest(UUID.randomUUID().toString(), WatchTaskOperation.SUBMIT, text, threadId, questionId)
        latestSubmission = request.id
        receipt("Sending to phone; acceptance not yet confirmed.")
        request(request)
    }

    fun stop() {
        val id = state.value.threadId ?: return
        request(WatchTaskRequest(UUID.randomUUID().toString(), WatchTaskOperation.STOP, threadId = id))
    }

    fun failure(message: String) {
        mutable.value = state.value.copy(error = message)
    }

    @SuppressLint("UseKtx")
    private fun receipt(message: String) {
        mutable.value = state.value.copy(receipt = message)
        check(prefs.edit().putString("receipt", message).commit()) { "Could not save delivery status." }
    }

    private fun request(request: WatchTaskRequest) {
        val read = request.operation == WatchTaskOperation.LIST || request.operation == WatchTaskOperation.READ
        if (read) {
            readId?.let {
                pending.remove(it)
                assemblies.remove(it)
            }
            readId = request.id
        }
        mutable.value = state.value.copy(error = null)
        scope.launch {
            try {
                val node = phone()
                if (read && readId != request.id) return@launch
                pending[request.id] = node to request
                send(node, request)
                delay(30_000)
                if (pending.containsKey(request.id)) {
                    if (read) {
                        pending.remove(request.id)
                        assemblies.remove(request.id)
                        if (readId == request.id) readId = null
                        failure("Phone has not replied. Check the connection, update EVA on both devices, and refresh.")
                    } else if (request.id == latestSubmission || request.operation == WatchTaskOperation.STOP) {
                        receipt("Acceptance unconfirmed. Check recent conversations; this request has not been retried.")
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (read && readId != request.id) return@launch
                if (readId == request.id) readId = null
                if (read) pending.remove(request.id)
                failure("Phone communication failed (${error.javaClass.simpleName}). Check the connection and refresh.")
                if (!read) receipt("Delivery unconfirmed. Check recent conversations before submitting again.")
            }
        }
    }

    fun receive(
        node: String,
        chunk: WatchReplyChunk,
    ) {
        val (expectedNode, request) = pending[chunk.id] ?: return
        if (node != expectedNode) return
        val reply = assemblies.getOrPut(chunk.id) { WatchReplyAssembly() }.accept(chunk) ?: return
        pending.remove(chunk.id)
        assemblies.remove(chunk.id)
        if (readId == chunk.id) readId = null
        if (reply.status in setOf("unknown", "failed")) {
            if (request.operation == WatchTaskOperation.SUBMIT || request.operation == WatchTaskOperation.STOP) receipt(reply.message)
            failure(reply.message)
            return
        }
        when (request.operation) {
            WatchTaskOperation.SUBMIT -> {
                receipt(reply.message)
                if (latestSubmission == request.id) open(reply.threadId) else refresh()
            }

            WatchTaskOperation.STOP -> {
                receipt(reply.message)
                refresh()
            }

            WatchTaskOperation.LIST -> {
                if (state.value.threadId == null) {
                    mutable.value =
                        state.value.copy(
                            conversations =
                                if (request.before ==
                                    null
                                ) {
                                    reply.conversations
                                } else {
                                    (state.value.conversations + reply.conversations).distinctBy { it.id }
                                },
                            nextBefore = reply.nextBefore,
                        )
                }
            }

            WatchTaskOperation.READ -> {
                if (state.value.threadId == request.threadId) {
                    mutable.value =
                        state.value.copy(
                            entries =
                                if (request.before ==
                                    null
                                ) {
                                    reply.entries
                                } else {
                                    (reply.entries + state.value.entries).distinctBy { it.id }
                                },
                            status = reply.message,
                            question = reply.question,
                            questionId = reply.questionId,
                            nextBefore = reply.nextBefore,
                        )
                }
            }
        }
    }
}

data class WatchTaskUi(
    val threadId: String? = null,
    val conversations: List<WatchConversation> = emptyList(),
    val entries: List<WatchConversationEntry> = emptyList(),
    val status: String = "",
    val receipt: String = "",
    val error: String? = null,
    val question: String? = null,
    val questionId: String? = null,
    val nextBefore: String? = null,
)
