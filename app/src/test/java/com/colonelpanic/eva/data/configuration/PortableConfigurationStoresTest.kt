package com.colonelpanic.eva.data.configuration

import android.app.Application
import android.content.Context
import android.net.Uri
import com.colonelpanic.eva.adapters.declarative.PackageCodec
import com.colonelpanic.eva.conversation.prompt.PromptComponent
import com.colonelpanic.eva.conversation.prompt.PromptConfig
import com.colonelpanic.eva.data.PackageSettings
import com.colonelpanic.eva.data.PortablePackageSettings
import com.colonelpanic.eva.data.PromptStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class PortableConfigurationStoresTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val packageJson by lazy {
        generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .map { File(it, "docs/examples/org-agenda.json") }
            .first { it.isFile }
            .readText()
    }

    @Before
    fun clearPreferences() {
        context
            .getSharedPreferences("eva.packages", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context
            .getSharedPreferences("eva.prompt", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `package restore preserves exact bytes stable identity waits and endpoint without pretending credentials transferred`() {
        val instance = "00000000-0000-0000-0000-000000000001"
        val credential = EvaConfigurationCodec.packageSecretId(instance)
        val restored =
            PortablePackageSettings(
                repository = "https://plugins.example.test/index.json",
                bundledInstances = emptyMap(),
                installed =
                    listOf(
                        PortablePackage(
                            instance,
                            "https://plugins.example.test/index.json",
                            "https://plugins.example.test/org-agenda.json",
                            packageJson,
                        ),
                    ),
                waitMillis = mapOf("voice" to 11_000, instance to 43_000),
                services = listOf(HttpServiceBinding(instance, "https://agenda.example.test", credential)),
            )
        val settings = PackageSettings(context, listPackages = { emptyList() })

        assertTrue(settings.restore(restored).isEmpty())

        assertEquals(restored, settings.portable())
        assertEquals(packageJson, settings.imported().single().json)
        assertEquals(
            instance,
            settings
                .imported()
                .single()
                .identity.id,
        )
        assertEquals(
            PackageCodec.decode(packageJson).digest,
            settings
                .imported()
                .single()
                .definition.digest,
        )
        assertEquals(listOf(credential), settings.missingCredentials(restored.services))
        assertFalse(settings.load().single().configured)
        val entry = settings.state.value.single()
        assertEquals("https://agenda.example.test", entry.origin)
        assertFalse(entry.credentialAvailable)
    }

    @Test
    fun `bundled identity migration keeps matching names and reports build additions and removals`() {
        val current = PackageSettings(context, listPackages = { listOf("kept.json", "new.json") }, readPackage = { packageJson })
        val newId = current.portable().bundledInstances.getValue("new.json")
        val keptId = "00000000-0000-0000-0000-000000000010"
        val restored =
            PortablePackageSettings(
                repository = current.repositorySource,
                bundledInstances =
                    mapOf(
                        "kept.json" to keptId,
                        "removed.json" to "00000000-0000-0000-0000-000000000011",
                    ),
                installed = emptyList(),
                waitMillis = emptyMap(),
                services = emptyList(),
            )

        val notices = current.restore(restored)

        assertEquals(keptId, current.portable().bundledInstances.getValue("kept.json"))
        assertEquals(newId, current.portable().bundledInstances.getValue("new.json"))
        assertTrue(notices.any { it.contains("removed.json") && it.contains("not in this EVA build") })
        assertTrue(notices.any { it.contains("new.json") && it.contains("new on this EVA build") })
    }

    @Test
    fun `portable prompt restore leaves a previously selected standalone document untouched`() =
        runTest {
            val external = File(context.cacheDir, "standalone-prompt.yaml").apply { writeText("user-owned bytes\n") }
            context
                .getSharedPreferences("eva.prompt", Context.MODE_PRIVATE)
                .edit()
                .putString("prompt.document", Uri.fromFile(external).toString())
                .putString("prompt.documentName", external.name)
                .commit()
            val store = PromptStore(context)
            val config = PromptConfig(listOf(PromptComponent("portable", instruction = "Portable instructions.")))

            store.restorePortable("https://instructions.example.test/eva.yaml", config)

            assertEquals("user-owned bytes\n", external.readText())
            assertEquals(config, store.portableSnapshot().config)
            assertFalse(store.location.value.chosen)
            assertNull(context.getSharedPreferences("eva.prompt", Context.MODE_PRIVATE).getString("prompt.document", null))
        }
}
