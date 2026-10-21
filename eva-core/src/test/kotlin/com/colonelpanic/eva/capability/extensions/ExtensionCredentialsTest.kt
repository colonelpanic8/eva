package com.colonelpanic.eva.capability.extensions

import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.MemoryInvocationRepository
import com.colonelpanic.eva.capability.ToolProposal
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ExtensionCredentialsTest {
    private val descriptor =
        ExtensionProtocol
            .describe(
                extensionDescription.replace(
                    "\"title\":\"Example\",",
                    "\"title\":\"Example\",\"credentials\":[" +
                        "{\"name\":\"passphrase\",\"title\":\"Key passphrase\"," +
                        "\"description\":\"Unlocks the key.\",\"kind\":\"password\"}," +
                        "{\"name\":\"pin\",\"title\":\"PIN\",\"description\":\"Unlocks the app.\",\"kind\":\"password\"}],",
                ),
            ).descriptor!!
    private val fake = FakeExtensionConnector()
    private var seen: List<CredentialSpec>? = null

    private fun TestScope.dispatcher(
        source: ExtensionCredentialSource,
        journal: MemoryInvocationRepository,
    ): Pair<CapabilityDispatcher, CapabilityRegistry> {
        val backend =
            ExtensionBackend(
                extensionIdentity,
                descriptor,
                extensionCapability,
                ExtensionConnectionManager(fake) { testScheduler.currentTime },
                StandardTestDispatcher(testScheduler),
                credentials =
                    object : ExtensionCredentialSource {
                        override fun values(
                            identity: ExtensionIdentity,
                            declared: List<CredentialSpec>,
                        ): CredentialValues {
                            assertEquals(extensionIdentity, identity)
                            seen = declared
                            return source.values(identity, declared)
                        }

                        override fun rejected(
                            identity: ExtensionIdentity,
                            delivered: Map<String, String>,
                            message: String,
                        ) = source.rejected(identity, delivered, message)
                    },
            )
        val registry = CapabilityRegistry(mapOf(backend.definition.id to backend), listOf(backend.definition))
        return CapabilityDispatcher(registry, journal) to registry
    }

    @Test
    fun `provisioned credentials ride only the IPC arguments and never the journal or a replay`() =
        runTest {
            val journal = MemoryInvocationRepository()
            val (dispatcher, registry) =
                dispatcher({ _, _ -> CredentialValues.Ready(mapOf("passphrase" to "correct horse")) }, journal)
            fake.reply =
                ExtensionProtocol.encodeResult(ResultReply(ExecutionOutcome(InvocationStatus.COMPLETED, "Decrypted."), null, false))
            val proposal = ToolProposal("call", "extension.example.app.read", emptyMap(), "read", registry.snapshot.revision)

            val record = dispatcher.execute(proposal)

            assertEquals(InvocationStatus.COMPLETED, record.status)
            assertEquals(listOf("passphrase", "pin"), seen!!.map { it.name })
            val sent = Json.parseToJsonElement(fake.arguments!!).jsonObject
            assertEquals(JsonObject(mapOf("passphrase" to JsonPrimitive("correct horse"))), sent[ExtensionProtocol.CREDENTIALS_KEY])
            val journaled = journal.history().single()
            assertFalse((journaled.toString() + journaled.arguments).contains("correct horse"))
            assertFalse(journaled.arguments!!.containsKey(ExtensionProtocol.CREDENTIALS_KEY))

            assertEquals(journaled, dispatcher.execute(proposal))
            assertEquals(1, fake.submits)
        }

    @Test
    fun `without a provisioned value the reserved key is omitted`() =
        runTest {
            val (dispatcher, registry) = dispatcher(ExtensionCredentialSource.NONE, MemoryInvocationRepository())
            fake.reply = ExtensionProtocol.encodeResult(ResultReply(ExecutionOutcome(InvocationStatus.COMPLETED, "ok"), null, false))

            dispatcher.execute(ToolProposal("call", "extension.example.app.read", emptyMap(), "read", registry.snapshot.revision))

            assertNull(Json.parseToJsonElement(fake.arguments!!).jsonObject[ExtensionProtocol.CREDENTIALS_KEY])
        }

    @Test
    fun `a refused credential sends nothing and says what to do`() =
        runTest {
            val (dispatcher, registry) =
                dispatcher(
                    { _, _ -> CredentialValues.Refused("Key passphrase was saved for another signer. Re-enter it.") },
                    MemoryInvocationRepository(),
                )

            val record =
                dispatcher.execute(
                    ToolProposal("call", "extension.example.app.read", emptyMap(), "read", registry.snapshot.revision),
                )

            assertEquals(InvocationStatus.NOT_EXECUTED, record.status)
            assertTrue(record.message.startsWith("Key passphrase was saved for another signer. Re-enter it."))
            assertEquals(0, fake.submits)
        }

    @Test
    fun `a model cannot supply the reserved key itself`() {
        val arguments = """{"${ExtensionProtocol.CREDENTIALS_KEY}":{"passphrase":"guess"}}"""
        assertTrue(runCatching { ExtensionProtocol.arguments(extensionSchema, arguments) }.isFailure)
        assertTrue(
            runCatching { ExtensionProtocol.encodeArguments(extensionSchema, mapOf(ExtensionProtocol.CREDENTIALS_KEY to "x")) }.isFailure,
        )
        ExtensionProtocol.arguments(extensionSchema, arguments, delivered = true)
    }

    @Test
    fun `a rejection names only delivered values and reaches the source, while the journal keeps the provider's message`() =
        runTest {
            val rejections = mutableListOf<Pair<Map<String, String>, String>>()
            val source =
                object : ExtensionCredentialSource {
                    override fun values(
                        identity: ExtensionIdentity,
                        declared: List<CredentialSpec>,
                    ) = CredentialValues.Ready(mapOf("passphrase" to "wrong horse"))

                    override fun rejected(
                        identity: ExtensionIdentity,
                        delivered: Map<String, String>,
                        message: String,
                    ) {
                        rejections += delivered to message
                    }
                }
            val journal = MemoryInvocationRepository()
            val (dispatcher, registry) = dispatcher(source, journal)
            fake.reply =
                ExtensionProtocol.encodeResult(
                    ResultReply(
                        ExecutionOutcome(InvocationStatus.NOT_EXECUTED, "The key passphrase saved in EVA is wrong."),
                        "not_configured",
                        false,
                        rejectedCredentials = setOf("passphrase", "pin"),
                    ),
                )

            val record =
                dispatcher.execute(ToolProposal("call", "extension.example.app.read", emptyMap(), "read", registry.snapshot.revision))

            assertEquals(InvocationStatus.NOT_EXECUTED, record.status)
            assertEquals(listOf(mapOf("passphrase" to "wrong horse") to "The key passphrase saved in EVA is wrong."), rejections)
            assertFalse(journal.history().toString().contains("wrong horse"))
        }

    private val checkable = descriptor.credentials.first().copy(checkable = true)

    private suspend fun TestScope.check(reply: String): CredentialVerdict {
        fake.reply = reply
        return ExtensionConnectionManager(fake) { testScheduler.currentTime }
            .checkCredential(extensionIdentity, descriptor, checkable, "correct horse")
    }

    @Test
    fun `a check sends only the credential under test to the reserved capability and returns the provider's verdict`() =
        runTest {
            val verdict =
                check(
                    ExtensionProtocol.encodeResult(
                        ResultReply(
                            ExecutionOutcome(
                                InvocationStatus.COMPLETED,
                                "That passphrase does not unlock the key.",
                                JsonObject(mapOf("verdict" to JsonPrimitive("rejected"))),
                            ),
                            null,
                            false,
                        ),
                    ),
                )

            assertEquals(CredentialVerdict.Rejected("That passphrase does not unlock the key."), verdict)
            assertEquals(ExtensionProtocol.CHECK_CREDENTIAL, fake.capability)
            assertEquals(descriptor.revision, fake.revision)
            assertEquals("""{"_credentials":{"passphrase":"correct horse"}}""", fake.arguments)
            assertTrue(fake.id.matches(Regex("eva-[0-9a-f]{64}")))
        }

    @Test
    fun `a check that cannot reach a verdict says why`() =
        runTest {
            assertEquals(
                CredentialVerdict.Unverified("No PGP key is imported."),
                check(
                    ExtensionProtocol.encodeResult(
                        ResultReply(ExecutionOutcome(InvocationStatus.NOT_EXECUTED, "No PGP key is imported."), "not_configured", false),
                    ),
                ),
            )
            assertEquals(
                CredentialVerdict.Unverified("Example answered the check with a reply EVA could not read."),
                check(ExtensionProtocol.encodeResult(ResultReply(ExecutionOutcome(InvocationStatus.COMPLETED, "ok"), null, false))),
            )
            fake.bindFailure = true
            assertEquals(CredentialVerdict.Unverified("Extension could not be reached. Nothing was submitted."), check(""))
        }

    @Test
    fun `a credential the provider cannot check is never sent ahead of use`() =
        runTest {
            val verdict =
                ExtensionConnectionManager(fake) { testScheduler.currentTime }
                    .checkCredential(extensionIdentity, descriptor, descriptor.credentials.first(), "correct horse")

            assertEquals(
                CredentialVerdict.Unverified("Example cannot check Key passphrase ahead of time; EVA finds out on first use."),
                verdict,
            )
            assertEquals(0, fake.binds)
        }
}
