package com.colonelpanic.eva.capability.extensions

import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.MemoryInvocationRepository
import com.colonelpanic.eva.capability.SecretVault
import com.colonelpanic.eva.capability.ToolProposal
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ExtensionSecretsTest {
    private val descriptor = ExtensionProtocol.describe(extensionDescription).descriptor!!

    @Test
    fun `returned secrets reach only the vault while the journal and model get handles and labels`() =
        runTest {
            val fake = FakeExtensionConnector()
            val vault = SecretVault()
            val backend =
                ExtensionBackend(
                    extensionIdentity,
                    descriptor,
                    extensionCapability,
                    ExtensionConnectionManager(fake) { testScheduler.currentTime },
                    StandardTestDispatcher(testScheduler),
                    vault,
                )
            fake.reply =
                """{"protocolVersion":1,"status":"completed","reasonCode":null,"truncated":false,
                "content":[{"type":"text","text":"Decrypted github.com/me (hunter2-correct-horse)."}],
                "structuredContent":{"path":"github.com/me","note":"hunter2-correct-horse"},
                "secrets":{"password":{"value":"hunter2-correct-horse","label":"Password for github.com/me"},
                "otp":{"value":"123456","label":"Current one-time code for github.com/me"}}}"""
            val registry = CapabilityRegistry(mapOf(backend.definition.id to backend), listOf(backend.definition))
            val journal = MemoryInvocationRepository()

            val record =
                CapabilityDispatcher(registry, journal).execute(
                    ToolProposal("call", backend.definition.id, emptyMap(), "sign in", registry.snapshot.revision, threadId = "thread"),
                )

            assertEquals(InvocationStatus.COMPLETED, record.status)
            val journaled = journal.history().single()
            val visible = journaled.message + journaled.data + journaled.arguments
            listOf("hunter2-correct-horse", "123456").forEach { assertFalse(visible, visible.contains(it)) }
            val handles = journaled.data!!.getValue("secrets").jsonObject
            val password = handles.getValue("password").jsonObject
            val handle = password.getValue("handle").jsonPrimitive.content
            assertTrue(handle.matches(Regex("secret:[0-9a-f]{32}")))
            assertEquals(JsonPrimitive("Password for github.com/me"), password["label"])
            assertTrue(journaled.message.contains(handle))
            assertTrue(journaled.message.contains("Current one-time code for github.com/me"))
            assertEquals(JsonPrimitive("github.com/me"), journaled.data!!["path"])

            assertEquals("hunter2-correct-horse", vault.resolve("thread", handle)!!.value)
            val otp =
                handles
                    .getValue("otp")
                    .jsonObject
                    .getValue("handle")
                    .jsonPrimitive.content
            assertEquals("123456", vault.resolve("thread", otp)!!.value)
            assertNull(vault.resolve("other thread", handle))
            vault.retain(emptySet())
            assertNull(vault.resolve("thread", handle))
        }

    @Test
    fun `a reply that breaks the secret rules is unknown and stores nothing`() =
        runTest {
            val fake = FakeExtensionConnector()
            val vault = SecretVault()
            val backend =
                ExtensionBackend(
                    extensionIdentity,
                    descriptor,
                    extensionCapability,
                    ExtensionConnectionManager(fake) { testScheduler.currentTime },
                    StandardTestDispatcher(testScheduler),
                    vault,
                )
            fake.reply =
                """{"protocolVersion":1,"status":"failed","reasonCode":null,"truncated":false,"content":[],
                "secrets":{"password":{"value":"hunter2-correct-horse","label":"Password"}}}"""

            val outcome =
                backend.execute(
                    ToolProposal("call", backend.definition.id, emptyMap(), "sign in", "registry", threadId = "thread"),
                )

            assertEquals(InvocationStatus.UNKNOWN, outcome.status)
            assertFalse(outcome.toString().contains("hunter2"))
            assertEquals("text hunter2-correct-horse", vault.mask("text hunter2-correct-horse"))
        }
}
