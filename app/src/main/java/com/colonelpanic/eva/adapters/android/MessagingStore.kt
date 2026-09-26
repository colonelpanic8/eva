package com.colonelpanic.eva.adapters.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract.PhoneLookup
import android.provider.Telephony
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the phone's SMS and MMS threads. Group texts live in the MMS tables, so a conversation is
 * assembled from both sides rather than from `content://sms` alone, and every query is bounded
 * because a long-lived phone holds tens of thousands of messages.
 */
class MessagingStore(
    context: Context,
) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver

    suspend fun conversations(
        query: ConversationQuery,
        limit: Int,
    ): List<Conversation> =
        withContext(Dispatchers.IO) {
            val names = NameCache()
            if (query.isEmpty) {
                return@withContext readThreads(null).take(limit).map { it.toConversation(names) }
            }
            // Numbers are matched before naming anyone, so a number search can afford a deeper scan.
            val threads = readThreads(null, if (query.names.isEmpty()) MAX_SEARCHED_THREADS else MAX_SCANNED_THREADS)
            val numbers =
                threads
                    .flatMap(Thread::recipientIds)
                    .distinct()
                    .chunked(ContactLookups.MAX_CONTACT_IDS)
                    .fold(emptyMap<Long, String>()) { found, chunk -> found + addressesById(chunk) }
            val conversations =
                threads.mapNotNull { thread ->
                    val addresses = thread.recipientIds.mapNotNull(numbers::get)
                    if (!query.includesNumbers(addresses)) return@mapNotNull null
                    Conversation(thread.id, addresses.map(names::participant), thread.dateMillis, thread.snippet)
                }
            query.rank(conversations).take(limit)
        }

    suspend fun conversation(id: Long): Conversation? =
        withContext(Dispatchers.IO) {
            val names = NameCache()
            readThreads("${Telephony.Threads._ID} = ?" to arrayOf(id.toString()))
                .firstOrNull()
                ?.toConversation(names)
                ?: readThreads(null).take(MAX_SCANNED_THREADS).firstOrNull { it.id == id }?.toConversation(names)
        }

    /**
     * When each number's one-to-one thread last had a message, keyed by [ContactHistory.key]. Group
     * threads are left out: being in a group chat says little about who "text Sarah" means. Silent,
     * like caption keywords: a contacts search never asks for SMS access just to rank its results.
     */
    suspend fun lastMessaged(): Map<String, Long> =
        withContext(Dispatchers.IO) {
            if (ContextCompat.checkSelfPermission(app, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
                return@withContext emptyMap()
            }
            val direct = readThreads(null).filter { it.recipientIds.size == 1 }
            val numbers =
                direct
                    .map { it.recipientIds.single() }
                    .distinct()
                    .chunked(ContactLookups.MAX_CONTACT_IDS)
                    .fold(emptyMap<Long, String>()) { found, chunk -> found + addressesById(chunk) }
            buildMap {
                // Threads arrive newest first, so the first time a number appears is its latest.
                for (thread in direct) {
                    val key = numbers[thread.recipientIds.single()]?.let(ContactHistory::key) ?: continue
                    if (key.isNotEmpty() && key !in this) put(key, thread.dateMillis)
                }
            }
        }

    suspend fun messages(
        threadId: Long,
        limit: Int,
    ): List<ConversationMessage> =
        withContext(Dispatchers.IO) {
            val names = NameCache()
            (readSms(threadId, limit, names) + readMms(threadId, limit, names))
                .sortedBy { it.sentMillis }
                .takeLast(limit)
        }

    private data class Thread(
        val id: Long,
        val recipientIds: List<Long>,
        val dateMillis: Long,
        val snippet: String?,
    )

    private fun Thread.toConversation(names: NameCache) =
        Conversation(id, addresses(recipientIds).map { names.participant(it) }, dateMillis, snippet)

    private fun readThreads(
        selection: Pair<String, Array<String>>?,
        cap: Int = MAX_SCANNED_THREADS,
    ): List<Thread> {
        val cursor =
            runCatching {
                resolver.query(THREADS, THREAD_COLUMNS, selection?.first, selection?.second, "${Telephony.Threads.DATE} DESC")
            }.getOrNull() ?: return emptyList()
        return cursor.use {
            buildList {
                while (it.moveToNext() && size < cap) {
                    val recipients =
                        it
                            .getString(1)
                            ?.split(' ')
                            ?.mapNotNull(String::toLongOrNull)
                            .orEmpty()
                    add(Thread(it.getLong(0), recipients, it.getLong(2), it.string(3)))
                }
            }
        }
    }

    /** Thread rows name their recipients by ID; the numbers themselves live in one shared table. */
    private fun addresses(recipientIds: List<Long>): List<String> {
        val byId = addressesById(recipientIds)
        return recipientIds.mapNotNull(byId::get)
    }

    private fun addressesById(recipientIds: List<Long>): Map<Long, String> {
        if (recipientIds.isEmpty()) return emptyMap()
        val (selection, arguments) = ContactLookups.idSelection("_id", recipientIds)
        val cursor =
            runCatching {
                resolver.query(CANONICAL_ADDRESSES, arrayOf("_id", "address"), selection, arguments, null)
            }.getOrNull() ?: return emptyMap()
        val byId = mutableMapOf<Long, String>()
        cursor.use { rows ->
            while (rows.moveToNext()) {
                val address = rows.string(1) ?: continue
                byId[rows.getLong(0)] = address
            }
        }
        return byId
    }

    private fun readSms(
        threadId: Long,
        limit: Int,
        names: NameCache,
    ): List<ConversationMessage> {
        val cursor =
            runCatching {
                resolver.query(
                    Telephony.Sms.CONTENT_URI,
                    arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
                    "${Telephony.Sms.THREAD_ID} = ?",
                    arrayOf(threadId.toString()),
                    "${Telephony.Sms.DATE} DESC",
                )
            }.getOrNull() ?: return emptyList()
        return cursor.use {
            buildList {
                while (it.moveToNext() && size < limit) {
                    val incoming = it.getInt(3) == Telephony.Sms.MESSAGE_TYPE_INBOX
                    val sender = it.string(0)?.takeIf { _ -> incoming }?.let(names::participant)
                    add(ConversationMessage(incoming, sender, it.getLong(2), it.string(1).orEmpty()))
                }
            }
        }
    }

    /** Every group message is an MMS, so a thread read that skipped these would show only its replies. */
    private fun readMms(
        threadId: Long,
        limit: Int,
        names: NameCache,
    ): List<ConversationMessage> {
        val cursor =
            runCatching {
                resolver.query(
                    Telephony.Mms.CONTENT_URI,
                    arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX),
                    "${Telephony.Mms.THREAD_ID} = ?",
                    arrayOf(threadId.toString()),
                    "${Telephony.Mms.DATE} DESC",
                )
            }.getOrNull() ?: return emptyList()
        val rows =
            cursor.use {
                buildList {
                    while (it.moveToNext() && size < limit) add(Triple(it.getLong(0), it.getLong(1), it.getInt(2)))
                }
            }
        return rows.map { (id, seconds, box) ->
            val incoming = box == Telephony.Mms.MESSAGE_BOX_INBOX
            ConversationMessage(
                incoming = incoming,
                sender = if (incoming) mmsSender(id)?.let(names::participant) else null,
                // MMS timestamps are seconds since the epoch where SMS timestamps are milliseconds.
                sentMillis = seconds * 1000,
                body = mmsText(id),
            )
        }
    }

    private fun mmsText(messageId: Long): String {
        val cursor =
            runCatching {
                resolver.query(
                    PARTS,
                    arrayOf("_id", "ct", "text"),
                    "mid = ? AND ct = ?",
                    arrayOf(messageId.toString(), "text/plain"),
                    null,
                )
            }.getOrNull() ?: return ""
        return cursor
            .use {
                buildList {
                    while (it.moveToNext() && size < MAX_TEXT_PARTS) add(it.string(2) ?: partText(it.getLong(0)))
                }
            }.filter(String::isNotBlank)
            .joinToString(" ")
    }

    /** Longer part bodies are stored as files instead of in the `text` column. */
    private fun partText(partId: Long): String =
        runCatching {
            resolver.openInputStream(Uri.withAppendedPath(PARTS, partId.toString()))?.use { stream ->
                stream.bufferedReader().readText().take(MAX_PART_CHARS)
            }
        }.getOrNull().orEmpty()

    private fun mmsSender(messageId: Long): String? {
        val cursor =
            runCatching {
                resolver.query(
                    "${Telephony.Mms.CONTENT_URI}/$messageId/addr".toUri(),
                    arrayOf("address", "type"),
                    "type = ?",
                    arrayOf(FROM_ADDRESS.toString()),
                    null,
                )
            }.getOrNull() ?: return null
        return cursor.use { if (it.moveToFirst()) it.string(0) else null }?.takeUnless { it == INSERT_ADDRESS_TOKEN }
    }

    /** One number appears across many rows, and each name costs a contacts query. */
    private inner class NameCache {
        private val allowed =
            ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        private val names = mutableMapOf<String, String?>()

        fun participant(number: String) = ConversationParticipant(number.trim(), name(number.trim()))

        private fun name(number: String): String? {
            if (!allowed || number.isBlank()) return null
            return names.getOrPut(number) {
                runCatching {
                    resolver.query(
                        Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                        arrayOf(PhoneLookup.DISPLAY_NAME),
                        null,
                        null,
                        null,
                    )
                }.getOrNull()?.use { if (it.moveToFirst()) it.getString(0)?.takeIf(String::isNotBlank) else null }
            }
        }
    }

    private fun Cursor.string(index: Int) = getString(index)?.trim()?.takeIf(String::isNotBlank)

    companion object {
        const val MAX_SCANNED_THREADS = 200
        const val MAX_SEARCHED_THREADS = 1000
        const val MAX_TEXT_PARTS = 4
        const val MAX_PART_CHARS = 4000

        /** `PduHeaders.FROM`, which is not public API. */
        const val FROM_ADDRESS = 137
        const val INSERT_ADDRESS_TOKEN = "insert-address-token"

        private val THREADS = "content://mms-sms/conversations?simple=true".toUri()
        private val CANONICAL_ADDRESSES = "content://mms-sms/canonical-addresses".toUri()
        private val PARTS = "content://mms/part".toUri()
        private val THREAD_COLUMNS =
            arrayOf(
                Telephony.Threads._ID,
                Telephony.Threads.RECIPIENT_IDS,
                Telephony.Threads.DATE,
                Telephony.Threads.SNIPPET,
            )
    }
}
