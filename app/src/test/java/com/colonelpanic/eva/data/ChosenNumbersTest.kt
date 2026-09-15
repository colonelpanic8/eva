package com.colonelpanic.eva.data

import android.app.Application
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class, manifest = Config.NONE)
class ChosenNumbersTest {
    @Test fun countFollowsWhatIsHeldAndForgettingIsWrittenOut() =
        runTest {
            val context = RuntimeEnvironment.getApplication()
            context
                .getSharedPreferences("eva.chosenNumbers", 0)
                .edit()
                .clear()
                .commit()
            var changes = 0
            val numbers = ChosenNumbers(context, onChanged = { changes++ })
            assertEquals(0, numbers.count.value)

            numbers.record(listOf("+1 415 555 0101", "+1 415 555 0102"))
            assertEquals(2, numbers.count.value)
            // The same person reached again is the same key, not a second entry.
            numbers.record(listOf("415-555-0101"))
            assertEquals(2, numbers.count.value)

            // A restore replaces what is held without reporting a change back to the file it came from.
            val restored = changes
            numbers.replace(mapOf("5550103" to 1L))
            assertEquals(1, numbers.count.value)
            assertEquals(restored, changes)

            numbers.forget()
            assertEquals(0, numbers.count.value)
            assertTrue(numbers.all().isEmpty())
            assertEquals(restored + 1, changes)
            assertEquals(0, ChosenNumbers(context).count.value)
        }
}
