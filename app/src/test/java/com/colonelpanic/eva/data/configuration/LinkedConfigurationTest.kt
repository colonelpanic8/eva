package com.colonelpanic.eva.data.configuration

import com.colonelpanic.eva.conversation.prompt.PromptComponent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class LinkedConfigurationTest {
    @Test
    fun `invalid newly selected directory leaves the previous valid link active`() =
        runTest {
            var local = configuration(textModel = "initial-text")
            val linked = linked(snapshot = { local }, apply = { local = it })
            val first = directory("first", local)

            assertTrue(linked.attach(first) is LinkedConfigurationResult.Loaded)
            val invalid = FakeDirectory("invalid", mutableMapOf("eva.yaml" to "not: [valid"))
            assertNotNull(runCatching { linked.attach(invalid) }.exceptionOrNull())
            assertEquals("first", linked.linkedLabel())

            val externallyEdited = configuration(textModel = "external-text")
            first.files["eva.yaml"] = encoded(externallyEdited)
            assertTrue(linked.reload() is LinkedConfigurationResult.Loaded)
            assertEquals(externallyEdited, local)
        }

    @Test
    fun `external root edit wins a conflict without being overwritten`() =
        runTest {
            var local = configuration(textModel = "initial-text")
            val linked = linked(snapshot = { local }, apply = { local = it })
            val selected = directory("linked", local)
            linked.attach(selected)

            val external = configuration(textModel = "external-text")
            val externalBytes = encoded(external) + "# preserved external edit\n"
            selected.files["eva.yaml"] = externalBytes
            local = local.copy(models = local.models.copy(text = "unsaved-local-text"))

            assertEquals(
                LinkedConfigurationResult.Conflict(emptyList()),
                linked.localChange(),
            )
            assertEquals(externalBytes, selected.files["eva.yaml"])
            assertEquals(external, local)
        }

    @Test
    fun `external edit to a partial include wins without flattening the root`() =
        runTest {
            val initial = configuration(textModel = "included-text")
            var local = initial
            val root =
                EvaConfigurationCodec
                    .complete(initial)
                    .copy(
                        include = listOf("model.yaml"),
                        models =
                            ModelsPatch(
                                realtime = initial.models.realtime,
                                reasoningEffort = initial.models.reasoningEffort,
                            ),
                    )
            val selected =
                FakeDirectory(
                    "linked",
                    mutableMapOf(
                        "eva.yaml" to EvaConfigurationCodec.encode(root),
                        "model.yaml" to
                            EvaConfigurationCodec.encode(
                                EvaConfigurationDocument(models = ModelsPatch(text = initial.models.text)),
                            ),
                    ),
                )
            val linked = linked(snapshot = { local }, apply = { local = it })
            linked.attach(selected)
            val rootBefore = selected.files.getValue("eva.yaml")
            val externalInclude =
                EvaConfigurationCodec.encode(
                    EvaConfigurationDocument(models = ModelsPatch(text = "external-included-text")),
                ) + "# preserved include edit\n"
            selected.files["model.yaml"] = externalInclude
            local = local.copy(voice = local.voice.copy(lookupRetries = 9))

            assertTrue(linked.localChange() is LinkedConfigurationResult.Conflict)
            assertEquals(rootBefore, selected.files["eva.yaml"])
            assertEquals(externalInclude, selected.files["model.yaml"])
            assertEquals("external-included-text", local.models.text)
            assertEquals(initial.voice, local.voice)
        }

    @Test
    fun `include changed during root replacement restores exact root and applies external graph`() =
        runTest {
            val initial = configuration(textModel = "included-text")
            var local = initial
            val root =
                EvaConfigurationCodec.complete(initial).copy(
                    include = listOf("model.yaml"),
                    models = ModelsPatch(realtime = initial.models.realtime, reasoningEffort = initial.models.reasoningEffort),
                )
            val selected =
                FakeDirectory(
                    "linked",
                    mutableMapOf(
                        "eva.yaml" to EvaConfigurationCodec.encode(root),
                        "model.yaml" to
                            EvaConfigurationCodec.encode(
                                EvaConfigurationDocument(models = ModelsPatch(text = "included-text")),
                            ),
                    ),
                )
            val linked = linked(snapshot = { local }, apply = { local = it })
            linked.attach(selected)
            val exactRoot = selected.files.getValue("eva.yaml")
            val externalInclude =
                EvaConfigurationCodec.encode(EvaConfigurationDocument(models = ModelsPatch(text = "external-during-save"))) +
                    "# external bytes\n"
            local = local.copy(models = local.models.copy(text = "local-would-mask"), voice = local.voice.copy(lookupRetries = 9))
            selected.duringReplacement = { files -> files["model.yaml"] = externalInclude }

            assertEquals(LinkedConfigurationResult.Conflict(emptyList()), linked.localChange())
            assertEquals(exactRoot, selected.files["eva.yaml"])
            assertEquals(externalInclude, selected.files["model.yaml"])
            assertEquals("external-during-save", local.models.text)
            assertEquals(initial.voice, local.voice)
        }

    @Test
    fun `root and include changed after replacement preserve exact external bytes`() =
        runTest {
            val initial = configuration(textModel = "included-text")
            var local = initial
            val root =
                EvaConfigurationCodec.complete(initial).copy(
                    include = listOf("model.yaml"),
                    models = ModelsPatch(realtime = initial.models.realtime, reasoningEffort = initial.models.reasoningEffort),
                )
            val selected =
                FakeDirectory(
                    "linked",
                    mutableMapOf(
                        "eva.yaml" to EvaConfigurationCodec.encode(root),
                        "model.yaml" to
                            EvaConfigurationCodec.encode(
                                EvaConfigurationDocument(models = ModelsPatch(text = "included-text")),
                            ),
                    ),
                )
            val linked = linked(snapshot = { local }, apply = { local = it })
            linked.attach(selected)
            val externalInclude =
                EvaConfigurationCodec.encode(EvaConfigurationDocument(models = ModelsPatch(text = "external-included"))) +
                    "# exact include bytes\n"
            val externalRoot =
                EvaConfigurationCodec.encode(root.copy(voice = VoicePatch(lookupRetries = 6))) + "# exact root bytes\n"
            local = local.copy(models = local.models.copy(text = "local-would-mask"))
            selected.duringReplacement = { files ->
                files["model.yaml"] = externalInclude
                files["eva.yaml"] = externalRoot
            }

            assertEquals(LinkedConfigurationResult.Conflict(emptyList()), linked.localChange())
            assertEquals(externalRoot, selected.files["eva.yaml"])
            assertEquals(externalInclude, selected.files["model.yaml"])
            assertEquals("external-included", local.models.text)
            assertEquals(6, local.voice.lookupRetries)
        }

    @Test
    fun `invalid include introduced during replacement restores only Evas written root`() =
        runTest {
            val initial = configuration(textModel = "included-text")
            var local = initial
            val root =
                EvaConfigurationCodec.complete(initial).copy(
                    include = listOf("model.yaml"),
                    models = ModelsPatch(realtime = initial.models.realtime, reasoningEffort = initial.models.reasoningEffort),
                )
            val selected =
                FakeDirectory(
                    "linked",
                    mutableMapOf(
                        "eva.yaml" to EvaConfigurationCodec.encode(root),
                        "model.yaml" to
                            EvaConfigurationCodec.encode(
                                EvaConfigurationDocument(models = ModelsPatch(text = "included-text")),
                            ),
                    ),
                )
            val linked = linked(snapshot = { local }, apply = { local = it })
            linked.attach(selected)
            val exactRoot = selected.files.getValue("eva.yaml")
            local = local.copy(voice = local.voice.copy(lookupRetries = 9))
            selected.duringReplacement = { files -> files["model.yaml"] = "not: [valid" }

            assertNotNull(runCatching { linked.localChange() }.exceptionOrNull())
            assertEquals(exactRoot, selected.files["eva.yaml"])
            assertEquals("not: [valid", selected.files["model.yaml"])
        }

    @Test
    fun `failed replacement retains the old root and a retry can save`() =
        runTest {
            var local = configuration(textModel = "old-text")
            val linked = linked(snapshot = { local }, apply = { local = it })
            val selected = directory("linked", local)
            linked.attach(selected)
            val oldRoot = selected.files.getValue("eva.yaml")
            local = local.copy(models = local.models.copy(text = "new-text"))
            selected.failReplacement = true

            assertNotNull(runCatching { linked.localChange() }.exceptionOrNull())
            assertEquals(oldRoot, selected.files["eva.yaml"])
            assertEquals(
                "old-text",
                EvaConfigurationCodec
                    .resolve(reader = selected)
                    .configuration.models.text,
            )

            selected.failReplacement = false
            assertEquals(LinkedConfigurationResult.Saved(emptyList()), linked.localChange())
            assertEquals(
                "new-text",
                EvaConfigurationCodec
                    .resolve(reader = selected)
                    .configuration.models.text,
            )
        }

    @Test
    fun `setup warnings survive unchanged reload and unrelated local save`() =
        runTest {
            val warnings =
                listOf(
                    "Authorize android.role.ASSISTANT on this device.",
                    "Provision local credential provider/openai-api.",
                )
            var local = configuration()
            var applyCalls = 0
            val linked =
                LinkedConfiguration(
                    snapshot = { local },
                    apply = {
                        applyCalls++
                        local = it
                        ConfigurationApplyResult(warnings)
                    },
                )
            val selected = directory("linked", local)

            assertEquals(LinkedConfigurationResult.Loaded(warnings), linked.attach(selected))
            assertEquals(LinkedConfigurationResult.Loaded(warnings), linked.reload())
            assertEquals(1, applyCalls)

            local = local.copy(appearance = local.appearance.copy(dynamicColor = true))
            assertEquals(LinkedConfigurationResult.Saved(warnings), linked.localChange())
            assertEquals(LinkedConfigurationResult.Loaded(warnings), linked.reload())
            assertEquals(1, applyCalls)
        }

    @Test
    fun `linked desired setup survives fresh-device apply and unrelated local change`() =
        runTest {
            val missingCredential = SecretReference("provider/openai-api", "openai-api-key")
            val missingReply = "10123:com.example.chat:1700000000000:${"c".repeat(64)}"
            val missingGrant =
                PortableGrant(
                    instance = "installed:0:com.example.extension",
                    identity = "a".repeat(64),
                    digest = "b".repeat(64),
                    mutations = listOf("create"),
                )
            val desired =
                configuration(
                    credentials = listOf(missingCredential),
                    grants = listOf(missingGrant),
                    messagingReplies = listOf(missingReply),
                    authorizations = listOf("android.role.ASSISTANT"),
                )
            val warnings =
                listOf(
                    "Authorize android.role.ASSISTANT on this device.",
                    "Install or reapprove extension ${missingGrant.instance}.",
                    "Provision local credential ${missingCredential.id}.",
                    "Reapprove messaging replies for $missingReply.",
                )
            var local = configuration(textModel = "fresh-device-text")
            var linkedDesired: EvaConfiguration? = null
            val linked =
                LinkedConfiguration(
                    snapshot = {
                        val owner = requireNotNull(linkedDesired)
                        local.copy(
                            credentials = owner.credentials,
                            extensions = owner.extensions,
                            messaging = owner.messaging,
                            device = owner.device,
                        )
                    },
                    apply = { incoming ->
                        linkedDesired = incoming
                        local =
                            incoming.copy(
                                credentials = EvaConfiguration.Credentials(emptyList()),
                                extensions = EvaConfiguration.Extensions(emptyList()),
                                messaging = incoming.messaging.copy(replies = emptyList()),
                                device = EvaConfiguration.Device(emptyList()),
                            )
                        ConfigurationApplyResult(warnings)
                    },
                )
            val selected = directory("linked", desired)

            assertEquals(LinkedConfigurationResult.Loaded(warnings), linked.attach(selected))
            assertTrue(local.credentials.required.isEmpty())
            assertTrue(local.extensions.grants.isEmpty())
            assertTrue(local.messaging.replies.isEmpty())
            assertTrue(local.device.authorizations.isEmpty())

            local = local.copy(models = local.models.copy(text = "unrelated-local-edit"))
            assertEquals(LinkedConfigurationResult.Saved(warnings), linked.localChange())
            val saved = EvaConfigurationCodec.resolve(reader = selected).configuration

            assertEquals("unrelated-local-edit", saved.models.text)
            assertEquals(listOf(missingCredential), saved.credentials.required)
            assertEquals(listOf(missingGrant), saved.extensions.grants)
            assertEquals(listOf(missingReply), saved.messaging.replies)
            assertEquals(listOf("android.role.ASSISTANT"), saved.device.authorizations)
        }

    private fun linked(
        snapshot: suspend () -> EvaConfiguration,
        apply: suspend (EvaConfiguration) -> Unit,
    ) = LinkedConfiguration(
        snapshot = snapshot,
        apply = {
            apply(it)
            ConfigurationApplyResult()
        },
    )

    private fun directory(
        label: String,
        configuration: EvaConfiguration,
    ) = FakeDirectory(label, mutableMapOf("eva.yaml" to encoded(configuration)))

    private fun encoded(configuration: EvaConfiguration) = EvaConfigurationCodec.encode(EvaConfigurationCodec.complete(configuration))

    private fun configuration(
        textModel: String = "custom-text",
        credentials: List<SecretReference> = emptyList(),
        grants: List<PortableGrant> = emptyList(),
        messagingReplies: List<String> = emptyList(),
        authorizations: List<String> = emptyList(),
    ) = EvaConfiguration(
        models = EvaConfiguration.Models(textModel, "custom-realtime", "high", "medium"),
        voice = EvaConfiguration.Voice(3),
        appearance = EvaConfiguration.Appearance(dynamicColor = false),
        capabilities = EvaConfiguration.Capabilities(screenControl = true),
        messaging = EvaConfiguration.Messaging(enabled = messagingReplies.isNotEmpty(), replies = messagingReplies),
        prompt =
            EvaConfiguration.Prompt(
                source = "https://instructions.example.test/eva.yaml",
                components = listOf(PromptComponent("custom", instruction = "Be useful.")),
            ),
        packages =
            EvaConfiguration.Packages(
                repository = "https://plugins.example.test/index.json",
                installed = emptyList(),
                waitMillis = emptyMap(),
                services = emptyList(),
            ),
        services = EvaConfiguration.Services(emptyMap()),
        extensions = EvaConfiguration.Extensions(grants),
        spotify = EvaConfiguration.Spotify(clientId = null),
        credentials = EvaConfiguration.Credentials(credentials),
        remembered = EvaConfiguration.Remembered(chosenNumbers = emptyMap()),
        device = EvaConfiguration.Device(authorizations),
    )

    private class FakeDirectory(
        override val label: String,
        val files: MutableMap<String, String>,
    ) : ConfigurationDirectory {
        var failReplacement = false
        var duringReplacement: ((MutableMap<String, String>) -> Unit)? = null

        override fun read(path: String): String? = files[path]

        override fun replaceRoot(
            text: String,
            expectedRootFingerprint: String?,
        ) {
            val actual = files["eva.yaml"]?.let(EvaConfigurationCodec::fingerprint)
            require(actual == expectedRootFingerprint) { "Configuration changed outside EVA; reload it before editing." }
            if (failReplacement) throw IOException("Replacement failed before the old root was changed.")
            files["eva.yaml"] = text
            duringReplacement?.also { duringReplacement = null }?.invoke(files)
        }
    }
}
