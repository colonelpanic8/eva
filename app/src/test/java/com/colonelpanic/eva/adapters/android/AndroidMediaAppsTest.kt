package com.colonelpanic.eva.adapters.android

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class, manifest = Config.NONE)
class AndroidMediaAppsTest {
    /** A phone with no media apps is the empty list, not a failure that disables the adapter. */
    @Test fun scanOfABarePhoneIsEmptyAndDoesNotThrow() {
        val context = RuntimeEnvironment.getApplication()
        val started = System.nanoTime()
        val apps = AndroidMediaApps(context, AndroidMediaLauncher(context), AndroidMediaLibraryQueueClient(context)).scan()
        val millis = (System.nanoTime() - started) / 1_000_000
        assertEquals("scan took ${millis}ms", emptyList<DiscoveredMediaApp>(), apps)
    }
}
