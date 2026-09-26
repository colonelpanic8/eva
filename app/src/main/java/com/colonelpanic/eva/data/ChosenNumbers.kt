package com.colonelpanic.eva.data

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.content.edit
import com.colonelpanic.eva.adapters.android.PhoneNumberKey
import com.colonelpanic.eva.adapters.android.PlatformPhoneNumberKey
import com.colonelpanic.eva.adapters.android.RemembersNumbers
import com.colonelpanic.eva.capability.ExecutionBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The phone numbers EVA last texted or dialed, in E.164 as [PlatformPhoneNumberKey] writes them.
 * Only the number and a time are kept, never who it belongs to or what was said.
 */
class ChosenNumbers(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onChanged: () -> Unit = {},
) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("eva.chosenNumbers", Context.MODE_PRIVATE)

    init {
        val stale = prefs.all.keys.filterNot(PhoneNumberKey.E164::matches)
        if (stale.isNotEmpty()) prefs.edit { stale.forEach(::remove) }
    }

    private val mutableCount = MutableStateFlow(prefs.all.size)

    /** How many numbers are held, so the messaging screen can offer to forget them. */
    val count = mutableCount.asStateFlow()

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
            val key = PlatformPhoneNumberKey(app)
            val keys = numbers.map(key::of).filter(PhoneNumberKey.E164::matches)
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
            mutableCount.value = prefs.all.size
            onChanged()
        }

    @SuppressLint("UseKtx")
    suspend fun replace(values: Map<String, Long>) =
        withContext(Dispatchers.IO) {
            require(values.size <= MAX_NUMBERS)
            check(
                prefs
                    .edit()
                    .apply {
                        clear()
                        for ((key, value) in values) putLong(key, value)
                    }.commit(),
            ) { "Could not restore remembered choices." }
            mutableCount.value = values.size
        }

    /** Unlike a restore, forgetting is the user's own edit, so it is written back out. */
    suspend fun forget() {
        replace(emptyMap())
        onChanged()
    }

    private companion object {
        const val MAX_NUMBERS = 500
    }
}
