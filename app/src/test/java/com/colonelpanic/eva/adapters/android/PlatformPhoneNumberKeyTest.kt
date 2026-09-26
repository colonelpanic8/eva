package com.colonelpanic.eva.adapters.android

import android.app.Application
import android.telephony.TelephonyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class, manifest = Config.NONE)
class PlatformPhoneNumberKeyTest {
    private fun keyFor(simCountry: String): PhoneNumberKey {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context.getSystemService(TelephonyManager::class.java)).setSimCountryIso(simCountry)
        return PlatformPhoneNumberKey(context)
    }

    @Test
    fun `a national number reads as one from the SIM's country`() {
        val key = keyFor("us")
        assertEquals("+12025550100", key.of("(202) 555-0100"))
        assertEquals("+12025550100", key.of("+1 202 555 0100"))
        assertEquals("+12025550100", key.of("1-202-555-0100"))
        assertEquals("+442079460100", keyFor("gb").of("020 7946 0100"))
    }

    @Test
    fun `numbers sharing only trailing digits stay apart`() {
        val key = keyFor("us")
        assertNotEquals(key.of("+44 20 7946 0100"), key.of("202-946-0100"))
    }

    @Test
    fun `a short code keeps its digits`() {
        assertEquals("72975", keyFor("us").of("72975"))
    }
}
