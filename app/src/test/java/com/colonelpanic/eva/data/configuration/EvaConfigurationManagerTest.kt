package com.colonelpanic.eva.data.configuration

import android.Manifest
import com.colonelpanic.eva.EvaApplication
import com.colonelpanic.eva.adapters.declarative.PackageCodec
import com.colonelpanic.eva.adapters.declarative.PackageEffect
import com.colonelpanic.eva.capability.extensions.PackageIdentity
import com.colonelpanic.eva.conversation.prompt.PromptComponent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = EvaApplication::class)
class EvaConfigurationManagerTest {
    private val app: EvaApplication get() = RuntimeEnvironment.getApplication() as EvaApplication
    private val packageJson by lazy {
        generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .map { File(it, "docs/examples/org-agenda.json") }
            .first { it.isFile }
            .readText()
    }

    @Test
    fun `manager restores every effective non-secret setting and reports device provisioning`() =
        runTest {
            val baseline = app.configuration.snapshotForTest()
            val instance = "00000000-0000-0000-0000-000000000021"
            val definition = PackageCodec.decode(packageJson)
            val identity = PackageIdentity(instance)
            val mutation = definition.capabilities.first { it.effect != PackageEffect.READ }.name
            val credential = EvaConfigurationCodec.packageSecretId(instance)
            val target =
                baseline.copy(
                    models = EvaConfiguration.Models("gpt-portable-text", "gpt-portable-realtime", "high"),
                    voice = EvaConfiguration.Voice(8),
                    appearance = EvaConfiguration.Appearance(dynamicColor = true),
                    capabilities = EvaConfiguration.Capabilities(screenControl = false),
                    messaging = EvaConfiguration.Messaging(enabled = true, replies = listOf(LIVE_REPLY, CHANGED_SIGNER_REPLY)),
                    prompt =
                        EvaConfiguration.Prompt(
                            "https://instructions.example.test/eva.yaml",
                            listOf(PromptComponent("portable", enabled = false, instruction = "Portable prompt.")),
                        ),
                    packages =
                        baseline.packages.copy(
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
                            waitMillis = mapOf("voice" to 12_000, "typed" to 23_000, instance to 44_000),
                            services = listOf(HttpServiceBinding(instance, "https://agenda.example.test", credential)),
                        ),
                    extensions =
                        EvaConfiguration.Extensions(
                            listOf(PortableGrant(identity.instanceId, identity.key, definition.digest, listOf(mutation))),
                        ),
                    spotify = EvaConfiguration.Spotify("spotify-portable-client"),
                    credentials =
                        EvaConfiguration.Credentials(
                            listOf(
                                SecretReference("provider/openai-api", "openai-api-key"),
                                SecretReference(credential, "http-basic", "https://agenda.example.test"),
                            ),
                        ),
                    remembered = EvaConfiguration.Remembered(mapOf("4155551212" to 1_700_000_000_000)),
                    device = EvaConfiguration.Device(listOf("android.role.ASSISTANT", "android.notification-listener")),
                )
            val directory = MemoryDirectory(EvaConfigurationCodec.encode(EvaConfigurationCodec.complete(target)))
            val manager = EvaConfigurationManager(app, availableMessagingReplies = { setOf(LIVE_REPLY) })

            val result = manager.attachForTest(directory) as LinkedConfigurationResult.Loaded

            assertEquals("gpt-portable-text", app.settings.textModel)
            assertEquals("gpt-portable-realtime", app.settings.realtimeModel)
            assertEquals("high", app.settings.reasoningEffort)
            assertEquals(8, app.settings.voiceLookupRetries)
            assertTrue(app.appearance.dynamicColor)
            assertFalse(app.capabilities.screenControlEnabled)
            assertTrue(app.messagingSettings.state.value.enabled)
            assertEquals(setOf(LIVE_REPLY), app.messagingSettings.state.value.replies)
            assertEquals("spotify-portable-client", app.spotify.clientId.value)
            assertEquals(
                target.prompt.components,
                app.prompts
                    .portableSnapshot()
                    .config.components,
            )
            assertEquals(target.packages, app.packageSettings.portable().configuration())
            assertEquals(target.remembered.chosenNumbers, app.chosenNumbers.all())
            assertEquals(
                definition.digest,
                app.packageSettings
                    .imported()
                    .single()
                    .definition.digest,
            )
            assertEquals(
                identity.id,
                app.packageSettings
                    .imported()
                    .single()
                    .identity.id,
            )
            assertEquals(
                packageJson,
                app.packageSettings
                    .imported()
                    .single()
                    .json,
            )
            assertEquals(
                target.extensions.grants,
                manager
                    .snapshotForTest()
                    .extensions.grants,
            )
            assertEquals(target.messaging, manager.snapshotForTest().messaging)
            assertTrue(result.setupRequired.any { it.contains("provider/openai-api") })
            assertTrue(result.setupRequired.any { it.contains(credential) })
            assertTrue(result.setupRequired.any { it.contains("android.role.ASSISTANT") })
            assertTrue(result.setupRequired.any { it.contains("android.notification-listener") })
            assertTrue(result.setupRequired.any { it.contains(CHANGED_SIGNER_REPLY) })
            assertTrue(result.setupRequired.none { it.contains(LIVE_REPLY) && it.contains("exact app installation") })
            assertFalse(directory.text.contains("password"))

            app.appearance.saveDynamicColor(false)
            manager.localChangeForTest()
            val saved = EvaConfigurationCodec.resolve(reader = directory).configuration
            assertFalse(saved.appearance.dynamicColor)
            assertEquals(target.messaging, saved.messaging)
        }

    @Test
    fun `failure after settings mutation rolls every earlier store back`() =
        runTest {
            val baseline = app.configuration.snapshotForTest()
            val target =
                baseline.copy(
                    models = baseline.models.copy(text = "must-roll-back"),
                    appearance = baseline.appearance.copy(dynamicColor = !baseline.appearance.dynamicColor),
                    messaging = EvaConfiguration.Messaging(enabled = true, replies = listOf(LIVE_REPLY)),
                    prompt =
                        baseline.prompt.copy(
                            source = "https://instructions.example.test/rollback.yaml",
                            components = listOf(PromptComponent("rollback", instruction = "Must not remain.")),
                        ),
                    packages = baseline.packages.copy(repository = "https://plugins.example.test/rollback.json"),
                    remembered = EvaConfiguration.Remembered(mapOf("4155559999" to 99)),
                )
            val manager =
                EvaConfigurationManager(
                    app,
                    beforeGrantRestore = { error("injected grant persistence failure") },
                    availableMessagingReplies = { setOf(LIVE_REPLY) },
                )

            assertTrue(
                runCatching {
                    manager.attachForTest(MemoryDirectory(EvaConfigurationCodec.encode(EvaConfigurationCodec.complete(target))))
                }.isFailure,
            )

            val after = app.configuration.snapshotForTest()
            assertEquals(baseline.models, after.models)
            assertEquals(baseline.appearance, after.appearance)
            assertEquals(baseline.messaging, after.messaging)
            assertEquals(baseline.prompt, after.prompt)
            assertEquals(baseline.packages, after.packages)
            assertEquals(baseline.remembered, after.remembered)
        }

    @Test
    fun `resume reassesses unchanged requirements while explicit reload reapplies settings`() =
        runTest {
            val permission = Manifest.permission.READ_CONTACTS
            shadowOf(app).denyPermissions(permission)
            val baseline = app.configuration.snapshotForTest()
            val target =
                baseline.copy(
                    models = baseline.models.copy(text = "portable-reload-model"),
                    device = EvaConfiguration.Device(listOf(permission)),
                )
            val manager = EvaConfigurationManager(app)
            val directory = MemoryDirectory(EvaConfigurationCodec.encode(EvaConfigurationCodec.complete(target)))

            val initial = manager.attachForTest(directory) as LinkedConfigurationResult.Loaded
            assertTrue(initial.setupRequired.any { it.contains(permission) })

            app.settings.saveTextModel("resume-drift")
            shadowOf(app).grantPermissions(permission)
            val resumed = manager.reloadForTest(force = false) as LinkedConfigurationResult.Loaded
            assertTrue(resumed.setupRequired.none { it.contains(permission) })
            assertEquals("resume-drift", app.settings.textModel)

            app.settings.saveTextModel("force-drift")
            manager.reloadForTest(force = true)
            assertEquals("portable-reload-model", app.settings.textModel)
        }

    private fun com.colonelpanic.eva.data.PortablePackageSettings.configuration() =
        EvaConfiguration.Packages(repository, bundledInstances, installed, waitMillis, services)

    private class MemoryDirectory(
        var text: String,
    ) : ConfigurationDirectory {
        override val label = "memory"

        override fun read(path: String): String? = text.takeIf { path == EvaConfigurationCodec.FILE_NAME }

        override fun replaceRoot(
            text: String,
            expectedRootFingerprint: String?,
        ) {
            require(EvaConfigurationCodec.fingerprint(this.text) == expectedRootFingerprint)
            this.text = text
        }
    }

    private companion object {
        val LIVE_REPLY = "10123:com.example.chat:1700000000000:${"a".repeat(64)}"
        val CHANGED_SIGNER_REPLY = "10123:com.example.chat:1700000000000:${"b".repeat(64)}"
    }
}
