package com.colonelpanic.eva.capability.extensions

import com.colonelpanic.eva.capability.InvocationStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The documented wire fixtures must stay accepted by the codec that is the protocol's oracle. */
class ExtensionFixtureTest {
    private val docs =
        generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .map { File(it, "docs") }
            .first { File(it, "extension-protocol.md").isFile }

    @Test
    fun `describe fixture decodes and its result fixture satisfies the declared output schema`() {
        val descriptor = ExtensionProtocol.describe(File(docs, "examples/extension-describe.json").readText()).descriptor
        assertNotNull(descriptor)
        val agenda = descriptor!!.capabilities.first { it.name == "agenda" }
        assertEquals(Effect.READ, agenda.effect)
        assertEquals(ExtensionProtocol.DEFAULT_WAIT_MILLIS, descriptor.capabilities.first { it.name == "complete" }.maxWaitMillis)
        val reply =
            ExtensionProtocol.executeResult(
                File(docs, "examples/extension-result.json").readText(),
                outputSchema = agenda.outputSchema,
            )
        assertEquals(InvocationStatus.COMPLETED, reply.outcome.status)
        assertEquals(2, (reply.outcome.data!!.getValue("entries") as JsonArray).size)
        assertTrue(reply.outcome.message.startsWith("2 entries"))
    }

    @Test
    fun `secret result fixture decodes with its secrets apart from the structured content`() {
        val reply = ExtensionProtocol.executeResult(File(docs, "examples/extension-secret-result.json").readText())
        assertEquals(setOf("password", "otp"), reply.secrets.keys)
        assertEquals("Current one-time code for github.com/colonelpanic8", reply.secrets.getValue("otp").label)
        assertEquals(setOf("path", "username", "hasOtp"), reply.outcome.data!!.keys)
    }

    @Test
    fun `credential fixtures decode and the delivered arguments match what EVA builds`() {
        val descriptor = ExtensionProtocol.describe(File(docs, "examples/extension-credential-describe.json").readText()).descriptor!!
        assertEquals(listOf("pgp_passphrase"), descriptor.credentials.map { it.name })
        val capability = descriptor.capabilities.single()
        val arguments = File(docs, "examples/extension-credential-arguments.json").readText()
        ExtensionProtocol.arguments(capability.inputSchema, arguments, delivered = true)
        val built =
            ExtensionProtocol.withCredentials(
                ExtensionProtocol.encodeArguments(capability.inputSchema, mapOf("path" to "github.com/colonelpanic8")),
                mapOf("pgp_passphrase" to "correct horse battery staple"),
            )
        assertEquals(Json.parseToJsonElement(arguments), Json.parseToJsonElement(built))
    }

    @Test
    fun `published schemas parse and name the draft they use`() {
        val schemas = File(docs, "schemas").listFiles { file -> file.extension == "json" }.orEmpty()
        assertEquals(
            setOf("tool", "extension-result", "extension-descriptor", "package"),
            schemas.map { it.name.removeSuffix(".schema.json") }.toSet(),
        )
        schemas.forEach { file ->
            val schema = Json.parseToJsonElement(file.readText()).jsonObject
            assertEquals(file.name, JsonPrimitive("https://json-schema.org/draft/2020-12/schema"), schema["\$schema"])
        }
    }
}
