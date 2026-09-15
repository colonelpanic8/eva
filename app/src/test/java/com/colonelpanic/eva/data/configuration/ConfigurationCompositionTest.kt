package com.colonelpanic.eva.data.configuration

import com.colonelpanic.eva.conversation.prompt.PromptComponent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigurationCompositionTest {
    @Test
    fun `format and version are emitted and required`() {
        val encoded = EvaConfigurationCodec.encode(EvaConfigurationDocument())

        assertTrue(encoded.contains("format: eva\n"))
        assertTrue(encoded.contains("version: 1\n"))
        val missingFormat =
            assertThrows(IllegalArgumentException::class.java) {
                EvaConfigurationCodec.decode("version: 1\n")
            }
        val missingVersion =
            assertThrows(IllegalArgumentException::class.java) {
                EvaConfigurationCodec.decode("format: eva\n")
            }
        assertTrue(missingFormat.message.orEmpty().contains("format"))
        assertTrue(missingVersion.message.orEmpty().contains("version"))
    }

    @Test
    fun `full nondefault configuration encodes and resolves deterministically`() {
        val expected = fullConfiguration()
        val encoded = EvaConfigurationCodec.encode(EvaConfigurationCodec.complete(expected))

        assertEquals(encoded, EvaConfigurationCodec.encode(EvaConfigurationCodec.decode(encoded)))
        assertEquals(
            expected,
            EvaConfigurationCodec.resolve(reader(mapOf(EvaConfigurationCodec.FILE_NAME to encoded))).configuration,
        )
    }

    @Test
    fun `nested includes are relative and later layers then root take precedence`() {
        val original = fullConfiguration()
        val deep =
            EvaConfigurationCodec.complete(
                original.copy(models = original.models.copy(text = "deep-text", realtime = "deep-realtime")),
            )
        val middle =
            EvaConfigurationDocument(
                include = listOf("deep.yaml"),
                models = ModelsPatch(text = "middle-text"),
            )
        val late = EvaConfigurationDocument(models = ModelsPatch(realtime = "late-realtime"))
        val root =
            EvaConfigurationDocument(
                include = listOf("stack/middle.yaml", "late.yaml"),
                models = ModelsPatch(text = "root-text"),
                voice = VoicePatch(lookupRetries = 7),
            )
        val resolved =
            EvaConfigurationCodec
                .resolve(
                    reader(
                        mapOf(
                            "eva.yaml" to EvaConfigurationCodec.encode(root),
                            "stack/middle.yaml" to EvaConfigurationCodec.encode(middle),
                            "stack/deep.yaml" to EvaConfigurationCodec.encode(deep),
                            "late.yaml" to EvaConfigurationCodec.encode(late),
                        ),
                    ),
                ).configuration

        assertEquals("root-text", resolved.models.text)
        assertEquals("late-realtime", resolved.models.realtime)
        assertEquals(7, resolved.voice.lookupRetries)
        assertEquals(original.appearance, resolved.appearance)
        assertEquals(original.packages, resolved.packages)
    }

    @Test
    fun `UI override keeps partial inherited fields linked to later external edits`() {
        val original = fullConfiguration()
        val included = EvaConfigurationDocument(models = ModelsPatch(text = original.models.text))
        val root =
            EvaConfigurationCodec
                .complete(original)
                .copy(
                    include = listOf("shared-model.yaml"),
                    models =
                        ModelsPatch(
                            realtime = original.models.realtime,
                            reasoningEffort = original.models.reasoningEffort,
                        ),
                )
        val files =
            mutableMapOf(
                "eva.yaml" to EvaConfigurationCodec.encode(root),
                "shared-model.yaml" to EvaConfigurationCodec.encode(included),
            )
        val before = EvaConfigurationCodec.resolve(reader(files))
        val edited = before.configuration.copy(voice = before.configuration.voice.copy(lookupRetries = 9))
        val override = EvaConfigurationCodec.overrides(edited, before.included, before.root.include)

        assertEquals(listOf("shared-model.yaml"), override.include)
        assertNull(override.models?.text)
        assertEquals(original.models.realtime, override.models?.realtime)
        assertEquals(original.models.reasoningEffort, override.models?.reasoningEffort)

        files["eva.yaml"] = EvaConfigurationCodec.encode(override)
        files["shared-model.yaml"] =
            EvaConfigurationCodec.encode(
                included.copy(models = included.models?.copy(text = "externally-edited-text")),
            )
        val after = EvaConfigurationCodec.resolve(reader(files)).configuration

        assertEquals("externally-edited-text", after.models.text)
        assertEquals(9, after.voice.lookupRetries)
        assertEquals(original.packages, after.packages)
    }

    @Test
    fun `empty collection overrides clear inherited collections`() {
        val original = fullConfiguration()
        val files =
            mutableMapOf(
                "eva.yaml" to
                    EvaConfigurationCodec.encode(
                        EvaConfigurationDocument(include = listOf("base.yaml")),
                    ),
                "base.yaml" to EvaConfigurationCodec.encode(EvaConfigurationCodec.complete(original)),
            )
        val inherited = EvaConfigurationCodec.resolve(reader(files))
        val cleared =
            inherited.configuration.copy(
                prompt = inherited.configuration.prompt.copy(components = emptyList()),
                messaging = inherited.configuration.messaging.copy(replies = emptyList()),
                packages =
                    inherited.configuration.packages.copy(
                        bundledInstances = emptyMap(),
                        installed = emptyList(),
                        waitMillis = emptyMap(),
                        services = emptyList(),
                    ),
                extensions = inherited.configuration.extensions.copy(grants = emptyList()),
                credentials = inherited.configuration.credentials.copy(required = emptyList()),
                remembered = inherited.configuration.remembered.copy(chosenNumbers = emptyMap()),
                device = inherited.configuration.device.copy(authorizations = emptyList()),
            )
        val override = EvaConfigurationCodec.overrides(cleared, inherited.included, inherited.root.include)

        assertEquals(emptyList<PromptComponent>(), override.prompt?.components)
        assertEquals(emptyList<String>(), override.messaging?.replies)
        assertEquals(emptyMap<String, String>(), override.packages?.bundledInstances)
        assertEquals(emptyList<PortablePackage>(), override.packages?.installed)
        assertEquals(emptyMap<String, Long>(), override.packages?.waitMillis)
        assertEquals(emptyList<HttpServiceBinding>(), override.packages?.services)
        assertEquals(emptyList<PortableGrant>(), override.extensions?.grants)
        assertEquals(emptyList<SecretReference>(), override.credentials?.required)
        assertEquals(emptyMap<String, Long>(), override.remembered?.chosenNumbers)
        assertEquals(emptyList<String>(), override.device?.authorizations)

        files["eva.yaml"] = EvaConfigurationCodec.encode(override)
        assertEquals(cleared, EvaConfigurationCodec.resolve(reader(files)).configuration)
    }

    @Test
    fun `messaging reply grants require an exact installation and signer identity`() {
        val invalid = fullConfiguration().copy(messaging = EvaConfiguration.Messaging(true, listOf("com.example.chat")))
        val encoded = EvaConfigurationCodec.encode(EvaConfigurationCodec.complete(invalid))

        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                EvaConfigurationCodec.resolve(reader(mapOf(EvaConfigurationCodec.FILE_NAME to encoded)))
            }

        assertTrue(failure.message.orEmpty().contains("messaging reply identity"))
    }

    @Test
    fun `include cycles and paths escaping the selected tree are rejected`() {
        val cycle =
            mapOf(
                "eva.yaml" to
                    EvaConfigurationCodec.encode(
                        EvaConfigurationDocument(include = listOf("a.yaml")),
                    ),
                "a.yaml" to
                    EvaConfigurationCodec.encode(
                        EvaConfigurationDocument(include = listOf("b.yaml")),
                    ),
                "b.yaml" to
                    EvaConfigurationCodec.encode(
                        EvaConfigurationDocument(include = listOf("a.yaml")),
                    ),
            )

        assertThrows(IllegalArgumentException::class.java) {
            EvaConfigurationCodec.resolve(reader(cycle))
        }
        listOf("../outside.yaml", "/absolute.yaml", "nested/../../outside.yaml", "nested\\outside.yaml").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) {
                EvaConfigurationCodec.decode("format: eva\nversion: 1\ninclude:\n- '$path'\n")
            }
        }
    }

    @Test
    fun `cumulative include content cannot exceed one configuration budget`() {
        val padding = "#" + "x".repeat(EvaConfigurationCodec.MAX_GRAPH_BYTES / 3) + "\n"
        val files =
            mapOf(
                "eva.yaml" to
                    EvaConfigurationCodec.encode(
                        EvaConfigurationDocument(include = listOf("first.yaml", "second.yaml", "third.yaml")),
                    ),
                "first.yaml" to EvaConfigurationCodec.encode(EvaConfigurationDocument()) + padding,
                "second.yaml" to EvaConfigurationCodec.encode(EvaConfigurationDocument()) + padding,
                "third.yaml" to
                    EvaConfigurationCodec.encode(EvaConfigurationCodec.complete(fullConfiguration())) + padding,
            )

        assertTrue(files.values.all { it.toByteArray().size <= EvaConfigurationCodec.MAX_FILE_BYTES })
        assertTrue(files.values.sumOf { it.toByteArray().size.toLong() } > EvaConfigurationCodec.MAX_GRAPH_BYTES.toLong())
        assertThrows(IllegalArgumentException::class.java) {
            EvaConfigurationCodec.resolve(reader(files))
        }
    }

    private fun reader(files: Map<String, String>) = ConfigurationReader(files::get)

    private fun fullConfiguration() =
        EvaConfiguration(
            models = EvaConfiguration.Models("custom-text", "custom-realtime", "high"),
            voice = EvaConfiguration.Voice(2),
            appearance = EvaConfiguration.Appearance(dynamicColor = true),
            capabilities = EvaConfiguration.Capabilities(screenControl = false),
            messaging = EvaConfiguration.Messaging(enabled = true, replies = listOf(MESSAGING_IDENTITY)),
            prompt =
                EvaConfiguration.Prompt(
                    source = "https://instructions.example.test/eva.yaml",
                    components =
                        listOf(
                            PromptComponent(
                                id = "custom",
                                title = "Custom instructions",
                                instruction = "Answer carefully.",
                            ),
                        ),
                ),
            packages =
                EvaConfiguration.Packages(
                    repository = "https://plugins.example.test/index.json",
                    bundledInstances = mapOf("caffeine.json" to BUNDLED_INSTANCE),
                    installed =
                        listOf(
                            PortablePackage(
                                instance = INSTALLED_INSTANCE,
                                source = "https://plugins.example.test/index.json",
                                url = "https://plugins.example.test/packages/example.json",
                                document = PACKAGE_DOCUMENT,
                            ),
                        ),
                    waitMillis = mapOf(INSTALLED_INSTANCE to 45_000, "typed" to 25_000, "voice" to 15_000),
                    services =
                        listOf(
                            HttpServiceBinding(
                                packageInstance = INSTALLED_INSTANCE,
                                origin = SERVICE_ORIGIN,
                                credential = EvaConfigurationCodec.packageSecretId(INSTALLED_INSTANCE),
                            ),
                        ),
                ),
            extensions =
                EvaConfiguration.Extensions(
                    grants =
                        listOf(
                            PortableGrant(
                                instance = "package:$INSTALLED_INSTANCE",
                                identity = "a".repeat(64),
                                digest = "b".repeat(64),
                                mutations = listOf("create"),
                            ),
                        ),
                ),
            spotify = EvaConfiguration.Spotify(clientId = "spotify-client"),
            credentials =
                EvaConfiguration.Credentials(
                    required =
                        listOf(
                            SecretReference(
                                id = EvaConfigurationCodec.packageSecretId(INSTALLED_INSTANCE),
                                kind = "http-basic",
                                endpoint = SERVICE_ORIGIN,
                            ),
                            SecretReference(
                                id = "provider/broker",
                                kind = "broker-link",
                                endpoint = "ws://localhost:8765/device",
                            ),
                        ),
                ),
            remembered = EvaConfiguration.Remembered(chosenNumbers = mapOf("4155551212" to 1_700_000_000_000)),
            device =
                EvaConfiguration.Device(
                    authorizations =
                        listOf(
                            "android.permission.READ_SMS",
                            "android.role.ASSISTANT",
                        ),
                ),
        )

    private companion object {
        const val BUNDLED_INSTANCE = "11111111-1111-1111-8111-111111111111"
        const val INSTALLED_INSTANCE = "22222222-2222-2222-8222-222222222222"
        const val SERVICE_ORIGIN = "https://service.example.test"
        val MESSAGING_IDENTITY = "10123:com.example.chat:1700000000000:${"a".repeat(64)}"
        val PACKAGE_DOCUMENT =
            """
            {
              "formatVersion": 1,
              "id": "community.example",
              "version": "1.2.3",
              "title": "Example service",
              "capabilities": [{
                "tool": {
                  "name": "create",
                  "description": "Create an item",
                  "inputSchema": {
                    "type": "object",
                    "properties": {},
                    "required": [],
                    "additionalProperties": false
                  }
                },
                "title": "Create item",
                "effects": "write",
                "execution": {
                  "mode": "synchronous",
                  "requiresForeground": false,
                  "maxWaitMillis": 45000,
                  "cancellation": "none",
                  "idempotency": "none",
                  "reconciliation": "none"
                },
                "binding": {
                  "kind": "http",
                  "origin": "https://service.example.test",
                  "method": "POST",
                  "path": "/items",
                  "parameters": [],
                  "credential": "example",
                  "requestBody": {"fields": {}},
                  "maxResponseBytes": 4096,
                  "result": {
                    "pointer": "",
                    "maxBytes": 1024,
                    "evidence": {"pointer": "/status", "equals": "created"}
                  }
                }
              }]
            }
            """.trimIndent()
    }
}
