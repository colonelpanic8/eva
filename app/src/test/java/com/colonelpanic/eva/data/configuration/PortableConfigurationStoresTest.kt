package com.colonelpanic.eva.data.configuration

import android.app.Application
import android.content.Context
import android.net.Uri
import com.colonelpanic.eva.adapters.declarative.DefaultPackages
import com.colonelpanic.eva.adapters.declarative.PackageCodec
import com.colonelpanic.eva.adapters.declarative.httpBindings
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
    fun `fresh package settings have no declarative packages until one is installed`() {
        val settings = PackageSettings(context)

        assertTrue(settings.load().isEmpty())
        assertTrue(settings.state.value.isEmpty())
        assertTrue(settings.portable().installed.isEmpty())
    }

    @Test
    fun `shipped defaults are adopted once and a removed default is not reinstalled`() {
        val assets =
            generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
                .map { File(it, "app/src/main/assets") }
                .first { it.isDirectory }
        val readAsset: (String) -> String = { path -> File(assets, path).readText() }
        val maps = DefaultPackages.all.single { it.id == "android.google-maps" }
        var changes = 0
        val settings = PackageSettings(context, onChanged = { changes++ }, readAsset = readAsset)

        val adopted = settings.adoptDefaults()
        assertEquals(DefaultPackages.all.map { it.id }, adopted.map { it.definition.id })
        val installed = adopted.single { it.definition.id == maps.id }
        assertEquals(maps.identity, installed.identity)
        assertEquals(maps.source, installed.source)
        assertEquals(maps.path, installed.url)
        assertEquals(readAsset(maps.path), installed.json)
        assertEquals(1, changes)
        assertEquals(DefaultPackages.all.map { it.id }, settings.portable().appliedDefaults)
        assertTrue(settings.adoptDefaults().isEmpty())
        assertEquals(adopted, settings.imported())

        settings.removePlugin(maps.identity.id)
        assertTrue(settings.adoptDefaults().isEmpty())
        assertEquals(DefaultPackages.all.size - 1, settings.imported().size)
        assertEquals(DefaultPackages.all.map { it.id }, PackageSettings(context).appliedDefaults())

        val restored = settings.portable().copy(appliedDefaults = emptyList())
        assertTrue(PackageSettings(context, readAsset = readAsset).restore(restored).isEmpty())
        val fresh = PackageSettings(context, readAsset = readAsset)
        assertTrue(fresh.appliedDefaults().isEmpty())
        val readopted = fresh.adoptDefaults()
        assertEquals(DefaultPackages.all.map { it.identity }, readopted.map { it.identity })
        assertEquals(DefaultPackages.all.size, fresh.imported().size)
        assertEquals(readAsset(maps.path), readopted.single { it.identity == maps.identity }.json)
    }

    @Test
    fun `package restore preserves exact bytes stable identity waits and endpoint without pretending credentials transferred`() {
        val instance = "00000000-0000-0000-0000-000000000001"
        val serviceName = "agenda"
        val credential = EvaConfigurationCodec.serviceSecretId(serviceName)
        val restored =
            PortablePackageSettings(
                repository = "https://plugins.example.test/index.json",
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
                services = emptyList(),
                httpServices = mapOf(serviceName to HttpServiceDefinition("https://agenda.example.test", credential)),
                serviceBindings = listOf(PackageServiceBinding(instance, "https://agenda.example.org", serviceName)),
            )
        val settings = PackageSettings(context)

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
        assertEquals(listOf(credential), settings.missingCredentials(restored))
        assertFalse(settings.load().single().configured)
        val entry = settings.state.value.single()
        assertEquals("https://agenda.example.test", entry.origin)
        assertEquals(serviceName, entry.serviceName)
        assertFalse(entry.credentialAvailable)
    }

    @Test
    fun `named services are reusable across packages and map multiple package origins independently`() {
        val first = "00000000-0000-0000-0000-000000000031"
        val second = "00000000-0000-0000-0000-000000000032"
        val multiOriginDocument =
            packageJson.replaceFirst(
                "\"origin\": \"https://agenda.example.org\"",
                "\"origin\": \"https://secondary.example.org\"",
            )
        val settings = PackageSettings(context)
        val restored =
            PortablePackageSettings(
                repository = "https://plugins.example.test/index.json",
                installed =
                    listOf(
                        PortablePackage(first, "index-one", "first", multiOriginDocument),
                        PortablePackage(second, "index-two", "second", packageJson),
                    ),
                waitMillis = emptyMap(),
                services = emptyList(),
                httpServices =
                    mapOf(
                        "agenda" to
                            HttpServiceDefinition(
                                "https://agenda.service.test",
                                EvaConfigurationCodec.serviceSecretId("agenda"),
                            ),
                        "secondary" to
                            HttpServiceDefinition(
                                "https://secondary.service.test",
                                EvaConfigurationCodec.serviceSecretId("secondary"),
                            ),
                    ),
                serviceBindings =
                    listOf(
                        PackageServiceBinding(first, "https://agenda.example.org", "agenda"),
                        PackageServiceBinding(first, "https://secondary.example.org", "secondary"),
                        PackageServiceBinding(second, "https://agenda.example.org", "agenda"),
                    ),
            )

        settings.restore(restored)

        assertEquals(restored, settings.portable())
        assertEquals(
            setOf("https://agenda.service.test", "https://secondary.service.test"),
            settings
                .load()
                .first { it.identity.id == first }
                .definition
                .httpBindings()
                .map { it.origin }
                .toSet(),
        )
        assertEquals(
            setOf("https://agenda.service.test"),
            settings
                .load()
                .first { it.identity.id == second }
                .definition
                .httpBindings()
                .map { it.origin }
                .toSet(),
        )
        assertEquals(
            setOf(EvaConfigurationCodec.serviceSecretId("agenda"), EvaConfigurationCodec.serviceSecretId("secondary")),
            settings.missingCredentials(restored).toSet(),
        )
    }

    @Test
    fun `available version one package service migrates to a named scoped service`() {
        val instance = "00000000-0000-0000-0000-000000000071"
        val legacyCredential = EvaConfigurationCodec.packageSecretId(instance)
        val settings = PackageSettings(context)
        val restored =
            PortablePackageSettings(
                repository = "https://plugins.example.test/index.json",
                installed =
                    listOf(
                        PortablePackage(
                            instance,
                            "https://plugins.example.test/index.json",
                            "https://plugins.example.test/org-agenda.json",
                            packageJson,
                        ),
                    ),
                waitMillis = emptyMap(),
                services = listOf(HttpServiceBinding(instance, "https://agenda.example.test", legacyCredential)),
            )

        settings.restore(restored)
        val portable = settings.portable()
        val name = "package-$instance"

        assertTrue(portable.services.isEmpty())
        assertEquals(
            HttpServiceDefinition("https://agenda.example.test", EvaConfigurationCodec.serviceSecretId(name)),
            portable.httpServices.getValue(name),
        )
        assertEquals(
            listOf(PackageServiceBinding(instance, "https://agenda.example.org", name)),
            portable.serviceBindings,
        )
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
