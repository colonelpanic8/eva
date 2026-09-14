package com.colonelpanic.eva.capability

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class RegistrySnapshotTest {
    private val definition = BundledCapabilities.definitions.first()
    private val backend =
        object : ExecutionBackend {
            override suspend fun unavailableReason(): String? = null

            override suspend fun execute(arguments: Map<String, String>) = ExecutionOutcome(InvocationStatus.COMPLETED, "done")
        }

    private fun registry() =
        CapabilityRegistry(
            mapOf(definition.id to backend),
            listOf(definition),
            mapOf(definition.id to "instance:binding-v1"),
        )

    @Test
    fun `snapshot owns immutable copies and hashing ignores object key order`() {
        val properties =
            linkedMapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(emptyMap()),
                "required" to kotlinx.serialization.json.JsonArray(emptyList()),
                "additionalProperties" to JsonPrimitive(false),
            )
        val mutableDefinition = definition.copy(inputSchema = JsonObject(properties))
        val backends = mutableMapOf(definition.id to backend)
        val definitions = mutableListOf(mutableDefinition)
        val registry = CapabilityRegistry(backends, definitions)
        val snapshot = registry.snapshot
        val reordered =
            CapabilityRegistry(
                backends,
                listOf(
                    mutableDefinition.copy(
                        inputSchema =
                            JsonObject(
                                properties.entries.reversed().associate {
                                    it.toPair()
                                },
                            ),
                    ),
                ),
            )
        assertEquals(snapshot.revision, reordered.snapshot.revision)
        properties.clear()
        definitions.clear()
        backends.clear()
        assertEquals(1, snapshot.catalog.size)
        assertEquals(
            4,
            snapshot.catalog
                .single()
                .inputSchema.size,
        )
        assertThrows(UnsupportedOperationException::class.java) { (snapshot.catalog as MutableList).clear() }
    }

    @Test
    fun `identity schema effects and presentation participate in revisions`() {
        val original = registry().snapshot.revision
        val identityChange =
            CapabilityRegistry(
                mapOf(definition.id to backend),
                listOf(definition),
                mapOf(definition.id to "instance2:binding-v1"),
            )
        assertNotEquals(original, identityChange.snapshot.revision)
        listOf(
            definition.copy(title = "New title"),
            definition.copy(source = CapabilitySource("different-owner", "New source")),
            definition.copy(readOnly = !definition.readOnly),
            definition.copy(description = "New behavior"),
            definition.copy(
                inputSchema =
                    JsonObject(definition.inputSchema + ("description" to JsonPrimitive("Narrowed"))),
            ),
        ).forEach { changed ->
            assertNotEquals(
                original,
                CapabilityRegistry(
                    mapOf(changed.id to backend),
                    listOf(changed),
                    mapOf(changed.id to "instance:binding-v1"),
                ).snapshot.revision,
            )
        }
    }

    @Test
    fun `invalid replacement preserves snapshot and removal rejects stale calls`() =
        runTest {
            val registry = registry()
            val old = registry.snapshot
            val proposal = ToolProposal("call", definition.id, mapOf("destination" to "Park"), "map Park", old.revision)
            try {
                registry.replace(mapOf(definition.id to backend), emptyList())
                error("Replacement should fail")
            } catch (_: IllegalArgumentException) {
                assertSame(old, registry.snapshot)
            }
            registry.replace(emptyMap(), emptyList())
            assertNull(registry.resolve(proposal))
            assertSame(backend, old.resolve(proposal))
        }

    @Test
    fun `removal during availability prevents dispatch and records rejection`() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val waiting =
                object : ExecutionBackend by backend {
                    override suspend fun unavailableReason(): String? {
                        entered.complete(Unit)
                        release.await()
                        return null
                    }

                    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome =
                        error("Must not execute removed binding")
                }
            val registry = CapabilityRegistry(mapOf(definition.id to waiting))
            val memory = MemoryInvocationRepository()
            val dispatcher = CapabilityDispatcher(registry, memory)
            val proposal = ToolProposal("call", definition.id, mapOf("destination" to "Park"), "map Park", registry.snapshot.revision)
            val action = async { dispatcher.execute(proposal) }
            entered.await()
            registry.replace(emptyMap())
            release.complete(Unit)
            assertEquals(InvocationStatus.NOT_EXECUTED, action.await().status)
            assertEquals(action.await(), dispatcher.execute(proposal))
        }

    @Test
    fun `replacement waits for durable dispatch admission but not external execution`() =
        runTest {
            val registry = registry()
            val old = registry.snapshot
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val proposal = ToolProposal("call", definition.id, mapOf("destination" to "Park"), "map Park", old.revision)
            val admission =
                async {
                    registry.commitDispatch(proposal) {
                        entered.complete(Unit)
                        release.await()
                    }
                }
            entered.await()
            val replacement = async { registry.replace(emptyMap()) }
            assertSame(old, registry.snapshot)
            release.complete(Unit)
            assertSame(backend, admission.await())
            replacement.await()
            assertNull(registry.resolve(proposal))
        }
}
