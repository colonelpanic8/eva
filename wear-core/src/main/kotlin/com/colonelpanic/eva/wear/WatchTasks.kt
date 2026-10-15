package com.colonelpanic.eva.wear

import kotlinx.serialization.Serializable

object WatchTasks {
    const val REQUEST = "/eva/tasks/request"
    const val REPLY = "/eva/tasks/reply"
}

@Serializable
data class WatchTaskRequest(
    val id: String,
    val operation: WatchTaskOperation,
    val text: String = "",
    val threadId: String? = null,
    val questionId: String? = null,
    val before: String? = null,
)

@Serializable
enum class WatchTaskOperation { SUBMIT, LIST, READ, STOP }

@Serializable
data class WatchTaskReply(
    val id: String,
    val status: String,
    val message: String,
    val threadId: String? = null,
    val taskId: String? = null,
    val conversations: List<WatchConversation> = emptyList(),
    val entries: List<WatchConversationEntry> = emptyList(),
    val questionId: String? = null,
    val question: String? = null,
    val nextBefore: String? = null,
)

@Serializable
data class WatchConversation(
    val id: String,
    val title: String,
    val status: String,
)

@Serializable
data class WatchConversationEntry(
    val id: String,
    val role: String,
    val text: String,
)

/** MessageClient payloads are limited to 100 KB; split replies without dropping conversation text. */
@Serializable
data class WatchReplyChunk(
    val id: String,
    val index: Int,
    val count: Int,
    val base64: String,
)

fun WatchTaskReply.chunks(): List<WatchReplyChunk> {
    val bytes = WearProtocol.encode(this)
    val count = (bytes.size + 47_999) / 48_000
    return (0 until count).map { index ->
        WatchReplyChunk(
            id,
            index,
            count,
            java.util.Base64.getEncoder().encodeToString(
                bytes.copyOfRange(
                    index * 48_000,
                    minOf(
                        bytes.size,
                        (index + 1) * 48_000,
                    ),
                ),
            ),
        )
    }
}

class WatchReplyAssembly {
    private val chunks = mutableMapOf<Int, ByteArray>()
    private var identity: Pair<String, Int>? = null

    fun accept(chunk: WatchReplyChunk): WatchTaskReply? {
        require(chunk.count > 0 && chunk.index in 0 until chunk.count) { "Invalid reply fragment." }
        val key = chunk.id to chunk.count
        require(identity == null || identity == key) { "Mismatched reply fragments." }
        identity = key
        chunks[chunk.index] =
            java.util.Base64
                .getDecoder()
                .decode(chunk.base64)
        if (chunks.size != chunk.count) return null
        val bytes = java.io.ByteArrayOutputStream()
        repeat(chunk.count) { bytes.write(chunks.getValue(it)) }
        return WearProtocol.decode<WatchTaskReply>(bytes.toByteArray()).also { require(it.id == chunk.id) }
    }
}
