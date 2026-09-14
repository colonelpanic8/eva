package com.colonelpanic.eva.data

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class PackageSettingsFailureTest {
    @Test
    fun `malformed package and initializer error do not hide a healthy bundled package`() {
        val json =
            generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
                .map { File(it, "app/src/main/assets/caffeine.json") }
                .first { it.isFile }
                .readText()
        val settings =
            PackageSettings(
                RuntimeEnvironment.getApplication(),
                listPackages = { listOf("invalid.json", "broken.json", "caffeine.json") },
                readPackage = { name ->
                    when (name) {
                        "invalid.json" -> "{"
                        "broken.json" -> throw ExceptionInInitializerError("bad regex")
                        else -> json
                    }
                },
            )
        assertEquals(listOf("Caffeine"), settings.state.value.map { it.title })
        assertEquals(1, settings.load().size)
        assertEquals(listOf("invalid.json", "broken.json"), settings.unavailable().map { it.packageName })
        assertTrue(settings.unavailable().all { it.descriptor == null && it.problem != null })
    }

    @Test
    fun `asset enumeration failure leaves settings usable with an unavailable entry`() {
        val settings = PackageSettings(RuntimeEnvironment.getApplication(), listPackages = { error("asset failure") })
        assertTrue(settings.load().isEmpty())
        assertTrue(settings.state.value.isEmpty())
        assertEquals("Bundled packages", settings.unavailable().single().packageName)
    }
}
