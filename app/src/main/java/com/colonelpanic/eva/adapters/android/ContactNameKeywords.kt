package com.colonelpanic.eva.adapters.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.core.content.ContextCompat
import com.colonelpanic.eva.providers.openai.OpenAiModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Supplies contact display names as speech-transcription keywords so spoken names are captioned
 * with the spelling the phone already knows. Reading is silent: it never prompts, and returns
 * nothing unless the user has already granted contacts access for an earlier lookup.
 */
class ContactNameKeywords(
    context: Context,
    private val history: suspend () -> ContactHistory = { ContactHistory.NONE },
) {
    private val context = context.applicationContext

    suspend fun names(): List<String> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val history = history()
        return withContext(Dispatchers.IO) { query(history) }
    }

    private fun query(history: ContactHistory): List<String> {
        val cursor =
            runCatching {
                context.contentResolver.query(
                    Phone.CONTENT_URI,
                    arrayOf(Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER, Phone.STARRED),
                    "${Phone.IN_VISIBLE_GROUP} = 1",
                    null,
                    null,
                )
            }.getOrNull() ?: return emptyList()
        val people = linkedMapOf<String, Person>()
        cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(0)?.trim()?.takeIf(String::isNotBlank) ?: continue
                if (name.length > MAX_NAME_LENGTH || name.none(Char::isLetter)) continue
                val used = it.getString(1)?.let(history::lastUsed)
                val person = people.getOrPut(name) { Person(name) }
                person.lastUsed = listOfNotNull(person.lastUsed, used).maxOrNull()
                person.starred = person.starred || it.getInt(2) == 1
            }
        }
        // The bound keeps the names the user actually speaks: people in touch lately, then favourites.
        // Android no longer reports how often a contact is used, so the phone's own history stands in.
        return people.values
            .sortedWith(compareByDescending<Person> { it.lastUsed ?: Long.MIN_VALUE }.thenByDescending { it.starred })
            .take(OpenAiModels.TRANSCRIPTION_KEYWORD_LIMIT)
            .map { it.name }
    }

    private class Person(
        val name: String,
        var lastUsed: Long? = null,
        var starred: Boolean = false,
    )

    private companion object {
        const val MAX_NAME_LENGTH = 64
    }
}
