package com.colonelpanic.eva.adapters.android

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.colonelpanic.eva.capability.InvocationStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URLDecoder

@RunWith(AndroidJUnit4::class)
class MapIntentBackendTest {
    @Test
    fun destinationCannotBecomeIntentFieldsOrAdditionalParameters() {
        val destination = "Park & cafe #1;intent://malicious/ café"
        val intent = MapIntentBackend.mapSearchIntent(destination)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("geo", intent.data?.scheme)
        assertEquals(destination, URLDecoder.decode(intent.data.toString().substringAfter("?q="), "UTF-8"))
        assertNull(intent.component)
        assertNull(intent.extras)
        assertEquals(0, intent.flags)
        assertEquals(1, intent.data.toString().count { it == '?' })
        assertEquals(0, intent.data.toString().count { it == '&' })
    }

    @Test
    fun absentSurfaceNeverLaunchesAnIntent() =
        runBlocking {
            val backend = MapIntentBackend(AndroidIntentHost())
            assertNotNull(backend.unavailableReason())
            val result = backend.execute(mapOf("destination" to "Golden Gate Park"))
            assertEquals(InvocationStatus.NOT_EXECUTED, result.status)
            assertEquals(AndroidIntentHost.SURFACE_LOST, result.message)
        }
}
