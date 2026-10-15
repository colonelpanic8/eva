package com.colonelpanic.eva.wear

import android.annotation.SuppressLint
import android.content.Context
import com.colonelpanic.eva.conversation.ConversationStore
import com.colonelpanic.eva.conversation.ThreadController
import com.colonelpanic.eva.conversation.ThreadItem
import com.colonelpanic.eva.diagnostics.EvaTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class PhoneWatchTasks(
    context: Context,
    private val controller: ThreadController,
    private val store: ConversationStore,
    private val deliver: suspend (String, WatchReplyChunk) -> Unit,
) {
    private val prefs = context.getSharedPreferences("eva.watch.tasks", Context.MODE_PRIVATE)
    private val admission = Mutex()

    suspend fun message(
        node: String,
        bytes: ByteArray,
    ) {
        val request =
            try {
                WearProtocol.decode<WatchTaskRequest>(bytes).also { require(UUID.fromString(it.id).toString() == it.id) }
            } catch (_: Exception) {
                EvaTrace.info("wear.task.invalid_request")
                return
            }
        val reply =
            try {
                val ready = controller.state.first { !it.isLoading }
                check(ready.errorMessage == null) { ready.errorMessage.orEmpty() }
                when (request.operation) {
                    WatchTaskOperation.SUBMIT, WatchTaskOperation.STOP -> admission.withLock { mutate(node, request) }
                    WatchTaskOperation.LIST -> list(request)
                    WatchTaskOperation.READ -> read(request)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                WatchTaskReply(
                    request.id,
                    "failed",
                    error.message ?: "Could not read the phone conversation. Open EVA on the phone for details.",
                )
            }
        try {
            for (chunk in reply.chunks()) deliver(node, chunk)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            EvaTrace.info("wear.task.reply_undelivered", "request" to request.id, "error" to error.javaClass.simpleName)
        }
    }

    @SuppressLint("UseKtx")
    private suspend fun mutate(
        node: String,
        request: WatchTaskRequest,
    ): WatchTaskReply {
        val key = "$node:${request.id}"
        val fingerprint =
            java.security.MessageDigest.getInstance("SHA-256").digest(WearProtocol.encode(request)).joinToString("") {
                "%02x".format(it)
            }
        prefs.getString(key, null)?.let {
            require(prefs.getString("$key.digest", null) == fingerprint) { "This request identity was already used for different input." }
            return WearProtocol.decode(it.toByteArray())
        }
        require(request.operation != WatchTaskOperation.SUBMIT || request.text.isNotBlank()) { "The dictated request was empty." }
        val reserved =
            WatchTaskReply(
                request.id,
                "unknown",
                "Submission outcome is unconfirmed. Check recent conversations on the phone; this request will not run again.",
                request.threadId,
            )
        check(
            prefs
                .edit()
                .putString(
                    key,
                    WearProtocol.encode(reserved).toString(Charsets.UTF_8),
                ).putString("$key.digest", fingerprint)
                .commit(),
        ) {
            "Could not save the watch request; no work was started."
        }
        val result =
            try {
                when (request.operation) {
                    WatchTaskOperation.SUBMIT -> {
                        val accepted = controller.submitBackground(request.text, request.threadId, request.questionId)
                        WatchTaskReply(
                            request.id,
                            "accepted",
                            "Accepted on phone. You can leave EVA; work continues.",
                            accepted.threadId,
                            accepted.taskId,
                        )
                    }

                    WatchTaskOperation.STOP -> {
                        val threadId = requireNotNull(request.threadId) { "Choose a conversation to stop." }
                        controller.taskSnapshots.value
                            .filter { it.threadId == threadId }
                            .forEach { controller.stopTask(it.taskId) }
                        WatchTaskReply(request.id, "stopping", "Stop requested. Actions already performed are not undone.", threadId)
                    }

                    else -> {
                        error("Not a mutation")
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                reserved.copy(
                    message =
                        "Could not confirm submission: ${error.message ?: error.javaClass.simpleName}. " +
                            "Check the conversation before starting it again.",
                )
            }
        check(prefs.edit().putString(key, WearProtocol.encode(result).toString(Charsets.UTF_8)).commit()) {
            "Could not save submission acknowledgement. Check recent conversations on the phone."
        }
        return result
    }

    private suspend fun status(threadId: String): String {
        val active = controller.taskSnapshots.value.filter { it.threadId == threadId }
        return if (active.isNotEmpty()) {
            active.joinToString(" · ") {
                it.state.name
                    .lowercase()
                    .replace('_', ' ')
            }
        } else {
            store
                .turns(threadId)
                .lastOrNull()
                ?.status
                ?.name
                ?.lowercase() ?: "empty"
        }
    }

    private suspend fun list(request: WatchTaskRequest): WatchTaskReply {
        val threads = store.threads()
        val start = request.before?.let { id -> threads.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.plus(1) } ?: 0
        val page = threads.drop(start).take(20)
        return WatchTaskReply(
            request.id,
            "listed",
            "Recent conversations",
            conversations = page.map { WatchConversation(it.id, it.title, status(it.id)) },
            nextBefore =
                page.lastOrNull()?.id?.takeIf {
                    start +
                        page.size <
                        threads.size
                },
        )
    }

    private suspend fun read(request: WatchTaskRequest): WatchTaskReply {
        val id = requireNotNull(request.threadId)
        require(store.thread(id) != null) { "This conversation no longer exists." }
        val entries =
            store.items(id, Int.MAX_VALUE).mapNotNull { item ->
                when (item) {
                    is ThreadItem.UserMessage -> WatchConversationEntry(item.id, "You", item.text)
                    is ThreadItem.AssistantMessage -> WatchConversationEntry(item.id, "EVA", item.text)
                    is ThreadItem.Notice -> WatchConversationEntry(item.id, "Status", item.text)
                    else -> null
                }
            }
        val end = request.before?.let { before -> entries.indexOfFirst { it.id == before }.takeIf { it >= 0 } } ?: entries.size
        val page = entries.take(end).takeLast(20)
        val question =
            controller.taskSnapshots.value
                .filter { it.threadId == id }
                .flatMap { it.questions }
                .firstOrNull { it.waiting }
        return WatchTaskReply(
            request.id,
            "read",
            status(id),
            id,
            entries = page,
            questionId = question?.questionId,
            question = question?.question,
            nextBefore =
                page.firstOrNull()?.id?.takeIf {
                    page.size <
                        end
                },
        )
    }
}
