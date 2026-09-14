package com.colonelpanic.eva.data

import android.content.Context
import androidx.core.content.edit
import com.colonelpanic.eva.adapters.android.ContactHistory
import com.colonelpanic.eva.adapters.android.RemembersNumbers
import com.colonelpanic.eva.capability.ExecutionBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The phone numbers EVA last texted or dialed, keyed the way [ContactHistory] compares numbers.
 * Only the number and a time are kept, never who it belongs to or what was said.
 */
class ChosenNumbers(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val prefs = context.applicationContext.getSharedPreferences("eva.chosenNumbers", Context.MODE_PRIVATE)

    suspend fun all(): Map<String, Long> =
        withContext(Dispatchers.IO) {
            prefs.all.mapNotNull { (key, value) -> (value as? Long)?.let { key to it } }.toMap()
        }

    /** [backend] with every number it reaches remembered; [argument] names the one holding the numbers. */
    fun remembering(
        backend: ExecutionBackend,
        argument: String,
    ): ExecutionBackend = RemembersNumbers(backend, argument, ::record)

    suspend fun record(numbers: List<String>) =
        withContext(Dispatchers.IO) {
            val now = clock()
            val keys = numbers.map(ContactHistory::key).filter { it.length == ContactHistory.MATCH_DIGITS }
            if (keys.isEmpty()) return@withContext
            val kept = all() + keys.associateWith { now }
            val stale =
                kept.entries
                    .sortedByDescending { it.value }
                    .drop(MAX_NUMBERS)
                    .map { it.key }
            prefs.edit {
                keys.forEach { putLong(it, now) }
                stale.forEach(::remove)
            }
        }

    private companion object {
        const val MAX_NUMBERS = 500
    }
}
