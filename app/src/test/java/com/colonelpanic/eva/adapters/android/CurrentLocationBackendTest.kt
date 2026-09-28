package com.colonelpanic.eva.adapters.android

import android.Manifest
import android.app.Application
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import com.colonelpanic.eva.capability.InvocationStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class CurrentLocationBackendTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val locations get() = shadowOf(app.getSystemService(LocationManager::class.java))

    @Test
    fun `without a location grant nothing is read`() =
        runTest {
            shadowOf(app).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
            assertEquals(CurrentLocationBackend.PERMISSION_HINT, CurrentLocationBackend(app).unavailableReason())
        }

    @Test
    fun `an approximate fix reports coordinates, the nearest address, and that it is approximate`() =
        runTest {
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
            shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            locations.setLocationEnabled(true)
            locations.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
            locations.simulateLocation(
                Location(LocationManager.NETWORK_PROVIDER).apply {
                    latitude = 37.7726
                    longitude = -122.439
                    accuracy = 1_500f
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    time = System.currentTimeMillis()
                },
            )
            val geocoder = Geocoder(app)
            shadowOf(geocoder).setFromLocation(
                listOf(Address(Locale.US).apply { setAddressLine(0, "226 Broderick St, San Francisco, CA 94117") }),
            )
            val backend = CurrentLocationBackend(app, geocoder)
            assertNull(backend.unavailableReason())

            val outcome = backend.execute(emptyMap())

            assertEquals(InvocationStatus.COMPLETED, outcome.status)
            assertTrue(outcome.message, outcome.message.startsWith("The phone is at 37.772600, -122.439000, within about 1500 m"))
            assertTrue(outcome.message.contains("approximate location only"))
            assertTrue(outcome.message, outcome.message.endsWith("Nearest address: 226 Broderick St, San Francisco, CA 94117."))
            val data = outcome.data!!
            assertEquals(-122.439, data.getValue("longitude").jsonPrimitive.double, 0.0)
            assertFalse(data.getValue("precise").jsonPrimitive.boolean)
        }
}
