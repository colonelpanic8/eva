package com.colonelpanic.eva.adapters.android

import android.Manifest
import android.content.Context
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Finds contacts by a heard name. Every contact with a phone number is read and ranked in memory
 * rather than filtered by SQL, because a misheard spelling matches nothing in a `LIKE` query; an
 * address book is small enough that reading it whole costs little.
 */
class ContactsQueryBackend(
    context: Context,
    private val host: AndroidIntentHost,
    private val history: suspend () -> ContactHistory = { ContactHistory.NONE },
) : ExecutionBackend {
    private val resolver = context.applicationContext.contentResolver

    override suspend fun unavailableReason(): String? = host.unavailableReason()

    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
        val query = arguments.getValue("query").trim()
        val field = ContactField.of(arguments["field"])
        if (!host.ensurePermission(Manifest.permission.READ_CONTACTS)) {
            return ExecutionOutcome(InvocationStatus.NOT_EXECUTED, PERMISSION_DENIED)
        }
        val contacts = withContext(Dispatchers.IO) { contacts() }
        return ExecutionOutcome(InvocationStatus.COMPLETED, ContactMatches.describe(query, contacts, field, history()))
    }

    private fun contacts(): List<ContactMatch> {
        val phones = phones()
        if (phones.isEmpty()) return emptyList()
        val names = structuredNames()
        val nicknames = nicknames()
        return phones.map { (id, match) ->
            val (given, family) = names[id] ?: (null to null)
            match.copy(givenName = given, familyName = family, nicknames = nicknames[id].orEmpty())
        }
    }

    private fun phones(): Map<Long, ContactMatch> {
        val cursor =
            resolver.query(
                Phone.CONTENT_URI,
                arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER, Phone.TYPE, Phone.LABEL),
                null,
                null,
                null,
            ) ?: return emptyMap()
        val found = linkedMapOf<Long, Pair<String, LinkedHashMap<String, ContactPhone>>>()
        cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(1)?.takeIf(String::isNotBlank) ?: continue
                val number = it.getString(2)?.trim()?.takeIf(String::isNotBlank) ?: continue
                val entry = found.getOrPut(it.getLong(0)) { name to linkedMapOf() }
                entry.second.getOrPut(number.filter(Char::isDigit)) { ContactPhone(number, kind(it.getInt(3), it.getString(4))) }
            }
        }
        return found.mapValues { (_, entry) -> ContactMatch(entry.first, entry.second.values.toList()) }
    }

    private fun structuredNames(): Map<Long, Pair<String?, String?>> =
        data(StructuredName.CONTENT_ITEM_TYPE, StructuredName.GIVEN_NAME, StructuredName.FAMILY_NAME).associate { (id, given, family) ->
            id to (given to family)
        }

    private fun nicknames(): Map<Long, List<String>> =
        data(Nickname.CONTENT_ITEM_TYPE, Nickname.NAME, null)
            .filter { it.second != null }
            .groupBy({ it.first }, { checkNotNull(it.second) })

    private fun data(
        mimeType: String,
        first: String,
        second: String?,
    ): List<Triple<Long, String?, String?>> {
        val cursor =
            resolver.query(
                Data.CONTENT_URI,
                listOfNotNull(Data.CONTACT_ID, first, second).toTypedArray(),
                "${Data.MIMETYPE} = ?",
                arrayOf(mimeType),
                null,
            ) ?: return emptyList()
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    val one = it.getString(1)?.trim()?.takeIf(String::isNotBlank)
                    val two = if (second != null) it.getString(2)?.trim()?.takeIf(String::isNotBlank) else null
                    add(Triple(it.getLong(0), one, two))
                }
            }
        }
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
