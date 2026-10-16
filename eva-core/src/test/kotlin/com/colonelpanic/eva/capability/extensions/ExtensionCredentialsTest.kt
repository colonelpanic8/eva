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
                credentials = { identity, declared ->
                    assertEquals(extensionIdentity, identity)
                    seen = declared
                    source.values(identity, declared)
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
}
