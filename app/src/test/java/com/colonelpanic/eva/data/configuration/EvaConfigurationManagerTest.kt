package com.colonelpanic.eva.data.configuration

import android.Manifest
import android.content.Context
import com.colonelpanic.eva.EvaApplication
import com.colonelpanic.eva.adapters.android.ContentProviderAccess
import com.colonelpanic.eva.adapters.declarative.PackageCodec
import com.colonelpanic.eva.adapters.declarative.PackageEffect
import com.colonelpanic.eva.adapters.declarative.configurePackage
import com.colonelpanic.eva.adapters.declarative.contentFixture
import com.colonelpanic.eva.capability.InteractionMode
import com.colonelpanic.eva.capability.extensions.ExtensionGrant
import com.colonelpanic.eva.capability.extensions.PackageIdentity
import com.colonelpanic.eva.conversation.prompt.PromptComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

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
    fun `restored content package retains Android setup across missing provider and later edits`() =
        runBlocking {
            val baseline = app.configuration.snapshotForTest()
            val source =
                contentFixture("mova-content")
                    .document
                    .toString()
            val instance = "00000000-0000-0000-0000-000000000055"
            val permission = ContentProviderAccess.MOVA_READ_TODOS
            val restored =
                baseline.copy(
                    packages =
                        baseline.packages.copy(
                            installed =
                                listOf(
                                    PortablePackage(
                                        instance,
                                        "https://example.org/mova.json",
                                        "https://example.org/mova.json",
                                        source,
                                    ),
                                ),
                        ),
                    device = EvaConfiguration.Device(baseline.device.authorizations + permission),
                )
            val folder = MemoryDirectory(EvaConfigurationCodec.encode(EvaConfigurationCodec.complete(restored)))
            val manager = EvaConfigurationManager(app)
            val loaded = manager.attachForTest(folder) as LinkedConfigurationResult.Loaded
            assertTrue(loaded.setupRequired.any { permission in it })
            assertTrue(loaded.setupRequired.any { "com.colonelpanic.mova.provider" in it })
            assertEquals(
                listOf("com.colonelpanic.mova.provider"),
                app.packageSettings.state.value
                    .single { it.id == instance }
                    .contentAuthorities,
            )
            app.appearance.saveDynamicColor(!baseline.appearance.dynamicColor)
            manager.localChangeForTest()
            val saved = EvaConfigurationCodec.resolve(folder).configuration
            assertTrue(permission in saved.device.authorizations)
            assertEquals(
                source,
                saved.packages.installed
                    .single()
                    .document,
            )
        }

    @Test
    fun `installed content dependencies enter portable authorizations even before a runtime grant`() =
        runBlocking {
            val baseline = app.configuration.snapshotForTest()
            val source =
                contentFixture("mova-content")
                    .document
                    .toString()
            val imported =
                baseline.copy(
                    packages =
                        baseline.packages.copy(
                            installed =
                                listOf(
                                    PortablePackage(
                                        "00000000-0000-0000-0000-000000000055",
                                        "https://example.org/mova.json",
                                        "https://example.org/mova.json",
                                        source,
                                    ),
                                ),
                        ),
                )
            val folder = MemoryDirectory(EvaConfigurationCodec.encode(EvaConfigurationCodec.complete(imported)))
            val manager = EvaConfigurationManager(app)
            manager.attachForTest(folder)
            manager.localChangeForTest()
            assertTrue(
                ContentProviderAccess.MOVA_READ_TODOS in
                    EvaConfigurationCodec
                        .resolve(folder)
                        .configuration.device.authorizations,
            )
        }

    @Test
    fun `failed first Git connection keeps the linked folder authoritative`() =
        runBlocking {
            val baseline = app.configuration.snapshotForTest()
            val folder = MemoryDirectory(EvaConfigurationCodec.encode(EvaConfigurationCodec.complete(baseline)))
            val manager = EvaConfigurationManager(app, beforeManagedConnect = { error("offline") })
            manager.attachForTest(folder)

            assertEquals(
                null,
                manager.configureGit(
                    "https://example.test/eva.git",
                    "main",
                    "EVA test device",
                    "eva@localhost",
                    "git",
                    "",
                ),
            )
            val failed = withTimeout(10_000) { manager.status.first { it.isError } }

            assertFalse(failed.gitEnabled)
            app.appearance.saveDynamicColor(!baseline.appearance.dynamicColor)
            assertTrue(manager.localChangeForTest() is LinkedConfigurationResult.Saved)
            assertEquals(
                !baseline.appearance.dynamicColor,
                EvaConfigurationCodec
                    .resolve(folder)
                    .configuration.appearance.dynamicColor,
            )
        }

    @Test
    fun `enabled managed checkout applies local configuration before offline sync`() =
        runBlocking {
            val baseline = app.configuration.snapshotForTest()
            val remote = "https://example.test/offline.git"
            val branch = "main"
            val identity =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest("$remote\u0000$branch".toByteArray())
                    .take(12)
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val checkout = File(File(app.getExternalFilesDir(null) ?: app.filesDir, "configuration-git"), identity)
            Git
                .init()
                .setDirectory(checkout)
                .setInitialBranch(branch)
                .call()
                .close()
            val expected = baseline.copy(appearance = EvaConfiguration.Appearance(!baseline.appearance.dynamicColor))
            File(checkout, EvaConfigurationCodec.FILE_NAME).writeText(
                EvaConfigurationCodec.encode(EvaConfigurationCodec.complete(expected)),
            )
            app
                .getSharedPreferences("eva.configuration", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("git.enabled", true)
                .putString("git.remote", remote)
                .putString("git.branch", branch)
                .putString("git.author.name", "EVA test device")
                .putString("git.author.email", "eva@localhost")
                .putString("git.username", "git")
                .commit()
            val manager = EvaConfigurationManager(app, beforeManagedConnect = { error("offline") })

            manager.start()
            val failed = withTimeout(10_000) { manager.status.first { it.isError } }

            assertTrue(failed.gitEnabled)
            assertEquals(expected.appearance.dynamicColor, app.appearance.dynamicColor)
            assertTrue(failed.linkedFolder?.contains("Managed Git checkout") == true)
        }

    @Test
    fun `manager restores every effective non-secret setting and reports device provisioning`() =
        runBlocking {
            val baseline = app.configuration.snapshotForTest()
            val instance = "00000000-0000-0000-0000-000000000021"
            val grantedInstance = "00000000-0000-0000-0000-000000000022"
            val credentialedJson = packageJson.replace("\"id\": \"community.org-agenda\"", "\"id\": \"test.portable-auth\"")
            val definition = PackageCodec.decode(credentialedJson)
            val uncredentialedJson =
                packageJson
                    .replace("\"id\": \"community.org-agenda\"", "\"id\": \"test.portable-grant\"")
                    .replace("\"credential\": \"org-agenda\",\n", "")
            val grantedDefinition =
                configurePackage(
                    PackageCodec.decode(uncredentialedJson),
                    mapOf("https://agenda.example.org" to "https://agenda.example.test"),
                )
            val identity = PackageIdentity(grantedInstance)
            val mutation = grantedDefinition.capabilities.first { it.effect != PackageEffect.READ }.name
            val serviceName = "shared-agenda"
            val credential = EvaConfigurationCodec.serviceSecretId(serviceName)
            val target =
                baseline.copy(
                    models = EvaConfiguration.Models("gpt-portable-text", "gpt-portable-realtime", "high", "medium"),
                    voice = EvaConfiguration.Voice(8, oneShotExternal = false),
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
                                        credentialedJson,
                                    ),
                                    PortablePackage(
                                        grantedInstance,
                                        "https://plugins.example.test/secondary-index.json",
                                        "https://plugins.example.test/org-agenda-uncredentialed.json",
                                        uncredentialedJson,
                                    ),
                                ),
                            waitMillis = mapOf("voice" to 12_000, "typed" to 23_000, grantedInstance to 44_000),
                            services = emptyList(),
                            serviceBindings =
                                listOf(
                                    PackageServiceBinding(instance, "https://agenda.example.org", serviceName),
                                    PackageServiceBinding(grantedInstance, "https://agenda.example.org", serviceName),
                                ),
                        ),
                    services =
                        EvaConfiguration.Services(
                            mapOf(serviceName to HttpServiceDefinition("https://agenda.example.test", credential)),
                        ),
                    extensions =
                        EvaConfiguration.Extensions(
                            listOf(PortableGrant(identity.instanceId, identity.key, grantedDefinition.digest, listOf(mutation))),
                        ),
                    spotify = EvaConfiguration.Spotify("spotify-portable-client"),
                    credentials =
                        EvaConfiguration.Credentials(
                            listOf(
                                SecretReference("provider/openai-api", "openai-api-key"),
                                SecretReference("provider/chatgpt", "chatgpt-account"),
                                SecretReference("provider/broker", "broker-link", "ws://localhost:8765/device"),
                                SecretReference("provider/spotify-account", "spotify-account"),
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
            assertEquals("medium", app.settings.voiceReasoningEffort)
            assertEquals(8, app.settings.voiceLookupRetries)
            assertFalse(app.settings.oneShotExternal)
            assertTrue(app.appearance.dynamicColor)
            assertFalse(app.capabilities.screenControlEnabled)
            assertTrue(app.messagingSettings.state.value.enabled)
            assertEquals(setOf(LIVE_REPLY), app.messagingSettings.state.value.replies)
            assertEquals("spotify-portable-client", app.spotify.clientId.value)
            assertEquals(target.prompt.source, app.prompts.portableSnapshot().source)
            assertEquals(
                target.prompt.components,
                app.prompts
                    .portableSnapshot()
                    .config.components,
            )
            assertEquals(target.packages, app.packageSettings.portable().configuration())
            assertEquals(target.services.http, app.packageSettings.portable().httpServices)
            assertEquals(44_000, app.packageSettings.budget(identity, null, InteractionMode.TYPED).effectiveMillis)
            assertEquals(target.remembered.chosenNumbers, app.chosenNumbers.all())
            assertEquals(
                definition.digest,
                app.packageSettings
                    .imported()
                    .single { it.identity.id == instance }
                    .definition.digest,
            )
            assertEquals(
                identity.id,
                app.packageSettings
                    .imported()
                    .single { it.identity.id == grantedInstance }
                    .identity.id,
            )
            assertEquals(
                uncredentialedJson,
                app.packageSettings
                    .imported()
                    .single { it.identity.id == grantedInstance }
                    .json,
            )
            val liveExtension =
                withTimeout(5_000) {
                    app.extensions.settings.first { settings ->
                        settings.entries.any {
                            it.installed.identity?.instanceId == identity.instanceId && it.enabled && mutation in it.mutations
                        }
                    }
                }.entries.singleOrNull { it.installed.identity?.instanceId == identity.instanceId }
            assertNotNull(
                app.extensions.settings.value.entries
                    .toString(),
                liveExtension,
            )
            assertEquals(grantedDefinition.digest, liveExtension?.installed?.descriptor?.digest)
            assertEquals(null, liveExtension?.installed?.problem)
            assertTrue(result.setupRequired.toString(), result.setupRequired.none { it.contains(identity.instanceId) })
            assertEquals(
                ExtensionGrant(identity.key, grantedDefinition.digest, setOf(mutation)),
                app.extensions.portableGrants()[identity.instanceId],
            )
            assertTrue(
                app.registry.snapshot.catalog
                    .any { it.id == "extension.package.$grantedInstance.$mutation" },
            )
            assertEquals(
                target.extensions.grants,
                manager
                    .snapshotForTest()
                    .extensions.grants,
            )
            assertEquals(target.messaging, manager.snapshotForTest().messaging)
            assertTrue(result.setupRequired.any { it.contains("provider/openai-api") })
            assertTrue(result.setupRequired.any { it.contains("provider/chatgpt") })
            assertTrue(result.setupRequired.any { it.contains("provider/broker") })
            assertTrue(result.setupRequired.any { it.contains("provider/spotify-account") })
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
            assertFalse(saved.voice.oneShotExternal)
            assertEquals(target.messaging, saved.messaging)
        }

    @Test
    fun `failure after settings mutation rolls every earlier store back`() =
        runTest {
            val baseline = app.configuration.snapshotForTest()
            val target =
                baseline.copy(
                    models = baseline.models.copy(text = "must-roll-back", voiceReasoningEffort = "xhigh"),
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
    fun `failure between package import and preferences restores prior package state`() =
        runTest {
            val baseline = app.configuration.snapshotForTest()
            val instance = "00000000-0000-0000-0000-000000000041"
            val target =
                baseline.copy(
                    packages =
                        baseline.packages.copy(
                            repository = "https://plugins.example.test/transaction.json",
                            installed =
                                listOf(
                                    PortablePackage(
                                        instance,
                                        "https://plugins.example.test/transaction.json",
                                        "https://plugins.example.test/transaction-package.json",
                                        packageJson,
                                    ),
                                ),
                        ),
                )
            val manager =
                EvaConfigurationManager(
                    app,
                    beforePackagePreferenceRestore = { error("injected between imports and preferences") },
                )

            val failure =
                runCatching {
                    manager.attachForTest(MemoryDirectory(EvaConfigurationCodec.encode(EvaConfigurationCodec.complete(target))))
                }.exceptionOrNull()

            assertTrue(failure?.message.orEmpty().contains("injected between imports and preferences"))
            assertEquals(baseline.packages, app.configuration.snapshotForTest().packages)
        }

    @Test
    fun `rollback attempts later stores and surfaces an individual rollback failure`() =
        runTest {
            val baseline = app.configuration.snapshotForTest()
            val target =
                baseline.copy(
                    models = baseline.models.copy(text = "rollback-step-target"),
                    appearance = baseline.appearance.copy(dynamicColor = !baseline.appearance.dynamicColor),
                    remembered = EvaConfiguration.Remembered(mapOf("4155554444" to 44)),
                )
            val manager =
                EvaConfigurationManager(
                    app,
                    beforeGrantRestore = { error("primary apply failure") },
                    beforeRollbackStep = { label -> if (label == "text model") error("injected rollback failure") },
                )

            val failure =
                runCatching {
                    manager.attachForTest(MemoryDirectory(EvaConfigurationCodec.encode(EvaConfigurationCodec.complete(target))))
                }.exceptionOrNull()

            assertTrue(failure?.message.orEmpty().contains("Rollback was incomplete: text model"))
            assertEquals("rollback-step-target", app.settings.textModel)
            assertEquals(baseline.appearance, app.configuration.snapshotForTest().appearance)
            assertEquals(baseline.remembered, app.configuration.snapshotForTest().remembered)
        }

    @Test
    fun `configured digest grant is live while stale digest remains desired and reported`() =
        runTest {
            val baseline = app.configuration.snapshotForTest()
            val instance = "00000000-0000-0000-0000-000000000051"
            val serviceName = "digest-service"
            val uncredentialedJson =
                packageJson
                    .replace("\"id\": \"community.org-agenda\"", "\"id\": \"test.stale-digest\"")
                    .replace("\"credential\": \"org-agenda\",\n", "")
            val configured =
                configurePackage(
                    PackageCodec.decode(uncredentialedJson),
                    mapOf("https://agenda.example.org" to "https://digest.example.test"),
                )
            val identity = PackageIdentity(instance)
            val mutation = configured.capabilities.first { it.effect != PackageEffect.READ }.name
            val staleGrant = PortableGrant(identity.instanceId, identity.key, "f".repeat(64), listOf(mutation))
            val target =
                baseline.copy(
                    packages =
                        baseline.packages.copy(
                            installed =
                                listOf(
                                    PortablePackage(
                                        instance,
                                        "https://plugins.example.test/digest-index.json",
                                        "https://plugins.example.test/digest-package.json",
                                        uncredentialedJson,
                                    ),
                                ),
                            services = emptyList(),
                            serviceBindings =
                                listOf(PackageServiceBinding(instance, "https://agenda.example.org", serviceName)),
                        ),
                    services =
                        EvaConfiguration.Services(
                            mapOf(serviceName to HttpServiceDefinition("https://digest.example.test")),
                        ),
                    extensions = EvaConfiguration.Extensions(listOf(staleGrant)),
                )
            val manager = EvaConfigurationManager(app)

            val result =
                manager.attachForTest(MemoryDirectory(EvaConfigurationCodec.encode(EvaConfigurationCodec.complete(target)))) as
                    LinkedConfigurationResult.Loaded

            assertTrue(result.setupRequired.any { it.contains(identity.instanceId) && it.contains("reapprove extension") })
            assertFalse(app.extensions.portableGrants().containsKey(identity.instanceId))
            assertEquals(listOf(staleGrant), manager.snapshotForTest().extensions.grants)
        }

    @Test
    fun `removed bundled package warning remains on unchanged reassessment`() =
        runTest {
            val baseline = app.configuration.snapshotForTest()
            val missing = "removed-from-build.json"
            val current = EvaConfigurationCodec.complete(baseline)
            val document =
                current.copy(
                    version = 2,
                    packages =
                        requireNotNull(current.packages).copy(
                            legacyBundledInstances = mapOf(missing to "00000000-0000-0000-0000-000000000061"),
                        ),
                )
            (1..2).forEach { version ->
                val legacy =
                    EvaConfigurationCodec.resolve(
                        reader = MemoryDirectory(EvaConfigurationCodec.encode(document.copy(version = version))),
                    )
                assertEquals(setOf(missing), legacy.configuration.packages.legacyBundledInstances.keys)
            }
            val manager = EvaConfigurationManager(app)
            val directory = MemoryDirectory(EvaConfigurationCodec.encode(document))

            val initial = manager.attachForTest(directory) as LinkedConfigurationResult.Loaded
            val reassessed = manager.reloadForTest(force = false) as LinkedConfigurationResult.Loaded

            assertTrue(initial.setupRequired.any { it.contains(missing) && it.contains("Browse to reinstall") })
            assertTrue(reassessed.setupRequired.any { it.contains(missing) && it.contains("Browse to reinstall") })
            val rewritten =
                EvaConfigurationCodec.encode(
                    EvaConfigurationCodec.complete(EvaConfigurationCodec.resolve(reader = directory).configuration),
                )
            assertTrue("bundledInstances" !in rewritten)
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
        EvaConfiguration.Packages(repository, installed, waitMillis, services, serviceBindings)

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
