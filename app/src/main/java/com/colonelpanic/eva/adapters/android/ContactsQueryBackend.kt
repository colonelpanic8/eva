package com.colonelpanic.eva.adapters.android

import android.Manifest
import android.content.Context
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
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
        val field = ContactField.of(arguments["field"])
        if (!host.ensurePermission(Manifest.permission.READ_CONTACTS)) {
            return ExecutionOutcome(InvocationStatus.NOT_EXECUTED, PERMISSION_DENIED)
        }
        val matches = withContext(Dispatchers.IO) { lookup(query, field) }
        return ExecutionOutcome(InvocationStatus.COMPLETED, ContactMatches.describe(query, matches))
    }

    private fun lookup(
        query: String,
        field: ContactField,
    ): List<ContactMatch> =
        when (val column = field.column) {
            null -> byDisplayName(query).ifEmpty { byFirstWord(query) }
            else -> byNamePart(query, column)
        }

    /** Only the whole-name search guesses: a dictated full name may not match how the contact is stored. */
    private fun byFirstWord(query: String): List<ContactMatch> =
        query
            .split(' ')
            .filter { it.length > 1 }
            .firstNotNullOfOrNull { token -> byDisplayName(token).ifEmpty { null } }
            .orEmpty()

    private fun byDisplayName(query: String): List<ContactMatch> =
        phones("${Phone.DISPLAY_NAME_PRIMARY} LIKE ? ESCAPE '\\'", arrayOf(ContactLookups.contains(query)))

    private fun byNamePart(
        query: String,
        column: String,
    ): List<ContactMatch> {
        val ids = linkedSetOf<Long>()
        resolver
            .query(
                Data.CONTENT_URI,
                arrayOf(Data.CONTACT_ID),
                "${Data.MIMETYPE} = ? AND $column LIKE ? ESCAPE '\\'",
                arrayOf(StructuredName.CONTENT_ITEM_TYPE, ContactLookups.contains(query)),
                null,
            )?.use { cursor -> while (cursor.moveToNext()) ids.add(cursor.getLong(0)) }
        if (ids.isEmpty()) return emptyList()
        val (selection, arguments) = ContactLookups.idSelection(Phone.CONTACT_ID, ids)
        return phones(selection, arguments)
    }

    private fun phones(
        selection: String,
        arguments: Array<String>,
    ): List<ContactMatch> {
        val cursor =
            resolver.query(
                Phone.CONTENT_URI,
                arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER, Phone.TYPE, Phone.LABEL),
                selection,
                arguments,
                "${Phone.DISPLAY_NAME_PRIMARY} COLLATE NOCASE ASC",
            ) ?: return emptyList()
        val found = linkedMapOf<Long, Pair<String, LinkedHashMap<String, ContactPhone>>>()
        cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(1)?.takeIf(String::isNotBlank) ?: continue
                val number = it.getString(2)?.trim()?.takeIf(String::isNotBlank) ?: continue
                val entry = found.getOrPut(it.getLong(0)) { name to linkedMapOf() }
                entry.second.getOrPut(number.filter(Char::isDigit)) { ContactPhone(number, kind(it.getInt(3), it.getString(4))) }
            }
        }
        return found.values.map { (name, numbers) -> ContactMatch(name, numbers.values.toList()) }
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
