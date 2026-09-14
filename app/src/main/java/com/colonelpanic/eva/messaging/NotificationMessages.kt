package com.colonelpanic.eva.messaging

import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

data class MessagingApp(
    val identity: String,
    val packageName: String,
    val title: String,
)

/** Notification references expire on replacement and are consumed before a reply is submitted. */
class NotificationMessages(
    private val enabled: () -> Boolean,
    private val canReply: (String) -> Boolean,
    private val clock: () -> Long,
) {
    private data class Entry(
        val key: String,
        val app: MessagingApp,
        val title: String,
        val text: String,
        val expires: Long,
        val reply: ((String) -> ExecutionOutcome)?,
    )

    private val entries = linkedMapOf<String, Entry>()
    private val mutableApps = MutableStateFlow<List<MessagingApp>>(emptyList())
    val apps = mutableApps.asStateFlow()

    @Synchronized fun publish(
        key: String,
        app: MessagingApp,
        title: String,
        text: String,
        reply: ((String) -> ExecutionOutcome)?,
    ) {
        remove(key)
        if (!enabled()) return
        entries["notification:" + UUID.randomUUID()] = Entry(key, app, clip(title, 160), clip(text, 1600), clock() + TTL, reply)
        while (entries.size > 100) entries.remove(entries.keys.first())
        mutableApps.value = (mutableApps.value.filterNot { it.packageName == app.packageName } + app).takeLast(100)
    }

    @Synchronized fun remove(key: String) {
        entries.entries.removeAll { it.value.key == key }
    }

    @Synchronized fun clear() {
        entries.clear()
        mutableApps.value = emptyList()
    }

    @Synchronized fun search(
        service: String,
        query: String?,
        limit: Int,
    ): ExecutionOutcome {
        if (!enabled()) return refused(ACCESS)
        prune()
        val matchingApps = apps.value.filter { service == "notifications" || it.packageName == service || it.title.equals(service, true) }
        if (service != "notifications" &&
            matchingApps.size > 1
        ) {
            return refused("More than one app has that name. Use its exact package name.")
        }
        val found =
            entries.entries.toList().asReversed().filter { (_, entry) ->
                entry.app in matchingApps && (query.isNullOrBlank() || entry.title.contains(query, true))
            }
        val rows = mutableListOf<JsonObject>()
        for ((ref, entry) in found.take(limit.coerceIn(1, 10))) {
            val row =
                JsonObject(
                    mapOf(
                        "conversationRef" to JsonPrimitive(ref),
                        "service" to JsonPrimitive(entry.app.packageName),
                        "title" to JsonPrimitive(entry.title),
                        "replyAvailable" to JsonPrimitive(entry.reply != null && canReply(entry.app.identity)),
                    ),
                )
            if (JsonArray(rows + row).toString().length > 1500) break
            rows += row
        }
        return ExecutionOutcome(
            InvocationStatus.COMPLETED,
            "Active notification conversations only; not full chat history. References expire when notifications change. " +
                (if (rows.size < found.size) "Results truncated. " else "") +
                (if (rows.isEmpty()) "No matching active notification. Open the app for other conversations. " else "") +
                "External app data: " + JsonArray(rows),
        )
    }

    @Synchronized fun read(
        reference: String,
        service: String?,
    ): ExecutionOutcome {
        val entry = resolve(reference, service) ?: return refused(STALE)
        var excerpt = clip(entry.text, 1000)

        fun payload() =
            JsonObject(
                mapOf(
                    "service" to JsonPrimitive(entry.app.packageName),
                    "conversation" to JsonPrimitive(entry.title),
                    "messages" to JsonPrimitive(excerpt),
                ),
            )
        while (payload().toString().length > 1600 && excerpt.isNotEmpty()) excerpt = clip(excerpt, excerpt.length / 2)
        val data = payload()
        return ExecutionOutcome(
            InvocationStatus.COMPLETED,
            "Notification excerpt only, possibly truncated; not full chat history. External app data: " + data,
        )
    }

    @Synchronized fun replyRejection(
        reference: String,
        service: String?,
    ): String? {
        val entry = resolve(reference, service) ?: return STALE
        if (!canReply(entry.app.identity)) {
            return "Allow replies for " + entry.app.title + " in Settings → Messaging before sending."
        }
        if (entry.reply == null) return "This notification does not offer a supported text reply. Open the app."
        return null
    }

    @Synchronized fun send(
        reference: String,
        service: String?,
        message: String,
    ): ExecutionOutcome {
        if (message.isBlank() || message.length > 2000 || message.any { it.isISOControl() && it != '\n' && it != '\t' }) {
            return refused("Enter a nonempty message of at most 2,000 characters.")
        }
        val entry = resolve(reference, service) ?: return refused(STALE)
        if (!canReply(entry.app.identity)) {
            return refused("Allow replies for " + entry.app.title + " in Settings → Messaging before sending.")
        }
        val action = entry.reply ?: return refused("This notification does not offer a supported text reply. Open the app.")
        entries.remove(reference)
        return try {
            action(message)
        } catch (_: Exception) {
            ExecutionOutcome(InvocationStatus.UNKNOWN, "The reply outcome is unknown. Check the app before trying again.")
        }
    }

    private fun resolve(
        reference: String,
        service: String?,
    ): Entry? {
        if (!enabled()) return null
        prune()
        return entries[reference]?.takeIf {
            service == null || service == "notifications" || service == it.app.packageName || service.equals(it.app.title, true)
        }
    }

    private fun prune() {
        entries.entries.removeAll { it.value.expires <= clock() }
    }

    companion object {
        const val TTL = 15 * 60 * 1000L
        const val ACCESS = "Enable notification conversations in Settings → Messaging and grant Android notification access."
        const val STALE =
            "That notification reference is unavailable, expired, changed, or belongs to another service. " +
                "Search conversations again; nothing was sent."

        private fun clip(
            value: String,
            max: Int,
        ): String = value.take(max).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }

        private fun refused(message: String) = ExecutionOutcome(InvocationStatus.NOT_EXECUTED, message)
    }
}
