package com.colonelpanic.eva.data.configuration

import com.colonelpanic.eva.conversation.prompt.PromptComponent
import com.colonelpanic.eva.providers.openai.OpenAiModels
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
        assertTrue(encoded.contains("version: 3\n"))
        val missingFormat =
            assertThrows(IllegalArgumentException::class.java) {
                EvaConfigurationCodec.decode("version: 2\n")
            }
        val missingVersion =
            assertThrows(IllegalArgumentException::class.java) {
                EvaConfigurationCodec.decode("format: eva\n")
            }
        assertTrue(missingFormat.message.orEmpty().contains("format"))
        assertTrue(missingVersion.message.orEmpty().contains("version"))
    }

    @Test
    fun `a document written before the speech leg had its own effort still resolves`() {
        val expected = fullConfiguration()
        val encoded = EvaConfigurationCodec.encode(EvaConfigurationCodec.complete(expected))
        val older = encoded.lines().filterNot { it.trim().startsWith("voiceReasoningEffort:") }.joinToString("\n")

        assertTrue(encoded.contains("voiceReasoningEffort:"))
        val resolved = EvaConfigurationCodec.resolve(reader(mapOf(EvaConfigurationCodec.FILE_NAME to older))).configuration
        assertEquals(OpenAiModels.VOICE_REASONING_EFFORT, resolved.models.voiceReasoningEffort)
        assertEquals(expected.models.reasoningEffort, resolved.models.reasoningEffort)
    }

    @Test
    fun `a document written before external conversation mode defaults to one shot`() {
        val expected = fullConfiguration()
        val complete = EvaConfigurationCodec.complete(expected)
        val encoded = EvaConfigurationCodec.encode(complete.copy(voice = complete.voice?.copy(oneShotExternal = null)))

        val resolved = EvaConfigurationCodec.resolve(reader(mapOf(EvaConfigurationCodec.FILE_NAME to encoded))).configuration

        assertEquals(true, resolved.voice.oneShotExternal)
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
    fun `version one package service remains readable and next complete write uses version three`() {
        val current = fullConfiguration()
        val legacyCredential = EvaConfigurationCodec.packageSecretId(INSTALLED_INSTANCE)
        val legacy =
            current.copy(
                packages =
                    current.packages.copy(
                        services = listOf(HttpServiceBinding(INSTALLED_INSTANCE, SERVICE_ORIGIN, legacyCredential)),
                        serviceBindings = emptyList(),
                    ),
                services = EvaConfiguration.Services(emptyMap()),
                credentials =
                    current.credentials
                        .copy(
                            required =
                                current.credentials.required.filterNot { it.id.startsWith("service/") } +
                                    SecretReference(legacyCredential, "http-basic", SERVICE_ORIGIN),
                        ).let { it.copy(required = it.required.sortedBy(SecretReference::id)) },
            )
        val versionOne = EvaConfigurationCodec.encode(EvaConfigurationCodec.complete(legacy).copy(version = 1))

        assertEquals(legacy, EvaConfigurationCodec.resolve(reader(mapOf("eva.yaml" to versionOne))).configuration)
        assertTrue(EvaConfigurationCodec.encode(EvaConfigurationCodec.complete(legacy)).contains("version: 3\n"))
    }

    @Test
    fun `provider endpoints and orphan HTTP credentials are rejected`() {
        val current = fullConfiguration()
        val ignoredProviderEndpoint =
            current.copy(
                credentials =
                    current.credentials.copy(
                        required =
                            current.credentials.required +
                                SecretReference("provider/openai-api", "openai-api-key", "https://ignored.example.test"),
                    ),
            )
        val orphan =
            current.copy(
                credentials =
                    current.credentials.copy(
                        required =
                            current.credentials.required +
                                SecretReference("service/orphan/basic", "http-basic", "https://orphan.example.test"),
                    ),
            )
        val missingPackageCredential =
            current.copy(
                services =
                    current.services.copy(
                        http = current.services.http.mapValues { (_, service) -> service.copy(credential = null) },
                    ),
                credentials =
                    current.credentials.copy(
                        required = current.credentials.required.filterNot { it.id.startsWith("service/") },
                    ),
            )

        assertThrows(IllegalArgumentException::class.java) {
            EvaConfigurationCodec.resolve(
                reader(
                    mapOf("eva.yaml" to EvaConfigurationCodec.encode(EvaConfigurationCodec.complete(ignoredProviderEndpoint))),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            EvaConfigurationCodec.resolve(reader(mapOf("eva.yaml" to EvaConfigurationCodec.encode(EvaConfigurationCodec.complete(orphan)))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EvaConfigurationCodec.resolve(
                reader(mapOf("eva.yaml" to EvaConfigurationCodec.encode(EvaConfigurationCodec.complete(missingPackageCredential)))),
            )
        }
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
                        installed = emptyList(),
                        waitMillis = emptyMap(),
                        services = emptyList(),
                        serviceBindings = emptyList(),
                    ),
                services = EvaConfiguration.Services(emptyMap()),
                extensions = inherited.configuration.extensions.copy(grants = emptyList()),
                credentials = inherited.configuration.credentials.copy(required = emptyList()),
                remembered = inherited.configuration.remembered.copy(chosenNumbers = emptyMap()),
                device = inherited.configuration.device.copy(authorizations = emptyList()),
            )
        val override = EvaConfigurationCodec.overrides(cleared, inherited.included, inherited.root.include)

        assertEquals(emptyList<PromptComponent>(), override.prompt?.components)
        assertEquals(emptyList<String>(), override.messaging?.replies)
        assertEquals(emptyList<PortablePackage>(), override.packages?.installed)
        assertEquals(emptyMap<String, Long>(), override.packages?.waitMillis)
        assertEquals(emptyList<PackageServiceBinding>(), override.packages?.serviceBindings)
        assertEquals(emptyMap<String, HttpServiceDefinition>(), override.services?.http)
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
                EvaConfigurationCodec.decode("format: eva\nversion: 2\ninclude:\n- '$path'\n")
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
            models = EvaConfiguration.Models("custom-text", "custom-realtime", "high", "medium"),
            voice = EvaConfiguration.Voice(2, oneShotExternal = false),
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
                    repositories = listOf("https://plugins.example.test/index.json"),
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
                    services = emptyList(),
                    serviceBindings =
                        listOf(
                            PackageServiceBinding(
                                packageInstance = INSTALLED_INSTANCE,
                                sourceOrigin = SERVICE_ORIGIN,
                                service = SERVICE_NAME,
                            ),
                        ),
                    appliedDefaults = listOf("android.google-maps"),
                ),
            services =
                EvaConfiguration.Services(
                    mapOf(
                        SERVICE_NAME to
                            HttpServiceDefinition(
                                SERVICE_ORIGIN,
                                EvaConfigurationCodec.serviceSecretId(SERVICE_NAME),
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
                                id = "provider/broker",
                                kind = "broker-link",
                                endpoint = "ws://localhost:8765/device",
                            ),
                            SecretReference(
                                id = EvaConfigurationCodec.serviceSecretId(SERVICE_NAME),
                                kind = "http-basic",
                                endpoint = SERVICE_ORIGIN,
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
        const val INSTALLED_INSTANCE = "22222222-2222-2222-8222-222222222222"
        const val SERVICE_ORIGIN = "https://service.example.test"
        const val SERVICE_NAME = "example-service"
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
                  "title": "Create item",
                  "description": "Create an item",
                  "inputSchema": {
                    "type": "object",
                    "properties": {},
                    "required": [],
                    "additionalProperties": false
                  }
                },
                "effects": "write",
                "execution": {
                  "mode": "synchronous",
                  "requiresForeground": false,
                  "maxWaitMillis": 45000
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
