package com.colonelpanic.eva.adapters.android

import android.Manifest
import android.content.Context
import android.provider.ContactsContract.CommonDataKinds.Phone
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ContactsQueryBackend(
    context: Context,
    private val host: AndroidIntentHost,
) : ExecutionBackend {
    private val resolver = context.applicationContext.contentResolver

    override suspend fun unavailableReason(): String? = host.unavailableReason()

    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
        val query = arguments.getValue("query").trim()
        if (!host.ensurePermission(Manifest.permission.READ_CONTACTS)) {
            return ExecutionOutcome(InvocationStatus.NOT_EXECUTED, PERMISSION_DENIED)
        }
        val matches = withContext(Dispatchers.IO) { lookup(query).ifEmpty { lookupByToken(query) } }
        return ExecutionOutcome(InvocationStatus.COMPLETED, ContactMatches.describe(query, matches))
    }

    /** A full name may not match how the contact is stored, so fall back to its first useful word. */
    private fun lookupByToken(query: String): List<ContactMatch> =
        query
            .split(' ')
            .filter { it.length > 1 }
            .firstNotNullOfOrNull { token -> lookup(token).ifEmpty { null } }
            .orEmpty()

    private fun lookup(query: String): List<ContactMatch> {
        val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val cursor =
            resolver.query(
                Phone.CONTENT_URI,
                arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER, Phone.TYPE, Phone.LABEL),
                "${Phone.DISPLAY_NAME_PRIMARY} LIKE ? ESCAPE '\\'",
                arrayOf("%$escaped%"),
                "${Phone.DISPLAY_NAME_PRIMARY} COLLATE NOCASE ASC",
            ) ?: return emptyList()
        val phones = linkedMapOf<Long, Pair<String, LinkedHashMap<String, ContactPhone>>>()
        cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(1)?.takeIf(String::isNotBlank) ?: continue
                val number = it.getString(2)?.trim()?.takeIf(String::isNotBlank) ?: continue
                val entry = phones.getOrPut(it.getLong(0)) { name to linkedMapOf() }
                entry.second.getOrPut(number.filter(Char::isDigit)) { ContactPhone(number, kind(it.getInt(3), it.getString(4))) }
            }
        }
        return phones.values.map { (name, numbers) -> ContactMatch(name, numbers.values.toList()) }
    }

    private fun kind(
        type: Int,
        label: String?,
    ): String =
        when (type) {
            Phone.TYPE_MOBILE -> "mobile"
            Phone.TYPE_HOME -> "home"
            Phone.TYPE_WORK -> "work"
            Phone.TYPE_MAIN -> "main"
            Phone.TYPE_WORK_MOBILE -> "work mobile"
            Phone.TYPE_CUSTOM -> label?.takeIf(String::isNotBlank)?.lowercase() ?: "other"
            else -> "other"
        }

    companion object {
        const val PERMISSION_DENIED = "Contacts access was not granted. Allow it in EVA's app settings or give the phone number directly."
    }
}
