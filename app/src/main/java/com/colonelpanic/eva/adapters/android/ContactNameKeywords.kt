package com.colonelpanic.eva.adapters.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract.Contacts
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
) {
    private val context = context.applicationContext

    suspend fun names(): List<String> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        return withContext(Dispatchers.IO) { query() }
    }

    private fun query(): List<String> {
        val cursor =
            runCatching {
                context.contentResolver.query(
                    Contacts.CONTENT_URI,
                    arrayOf(Contacts.DISPLAY_NAME_PRIMARY),
                    "${Contacts.IN_VISIBLE_GROUP} = 1",
                    null,
                    "${Contacts.TIMES_CONTACTED} DESC",
                )
            }.getOrNull() ?: return emptyList()
        val names = LinkedHashSet<String>()
        cursor.use {
            // Most contacted first, so the bound keeps the names the user actually speaks.
            while (it.moveToNext() && names.size < OpenAiModels.TRANSCRIPTION_KEYWORD_LIMIT) {
                val name = it.getString(0)?.trim()?.takeIf(String::isNotBlank) ?: continue
                if (name.length <= MAX_NAME_LENGTH && name.any(Char::isLetter)) names += name
            }
        }
        return names.toList()
    }

    private companion object {
        const val MAX_NAME_LENGTH = 64
    }
}
