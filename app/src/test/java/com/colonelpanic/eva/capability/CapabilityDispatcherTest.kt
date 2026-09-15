package com.colonelpanic.eva.capability

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class CapabilityDispatcherTest {
    private val repository = MemoryInvocationRepository()
    private var executions = 0
    private var unavailable: String? = null
    private var action: suspend () -> ExecutionOutcome = { ExecutionOutcome(InvocationStatus.HANDED_OFF, "Opened") }
    private val backend =
        object : ExecutionBackend {
            override suspend fun unavailableReason() = unavailable

            override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
                assertEquals(
                    InvocationStatus.DISPATCHING,
                    repository.records.values
                        .last()
                        .status,
                )
                executions++
                return action()
            }
        }
    private val registry = CapabilityRegistry(mapOf(CapabilityRegistry.MAP_SEARCH to backend))
    private val dispatcher = CapabilityDispatcher(registry, repository) { 10L }

    private fun proposal(
        id: String = "session:call",
        destination: String = "Golden Gate Park",
    ) = ToolProposal(
        id,
        CapabilityRegistry.MAP_SEARCH,
        mapOf("destination" to destination),
        "map $destination",
        registry.snapshot.revision,
    )

    @Test
    fun `duplicate delivery returns the original result without opening again`() =
        runTest {
            val first = dispatcher.execute(proposal())
            assertEquals(InvocationStatus.HANDED_OFF, first.status)
            assertEquals(first, dispatcher.execute(proposal()))
            assertEquals(1, executions)
            assertEquals(first, repository.history().single())
        }

    @Test
    fun `intentional repeat with new call ID is a new action`() =
        runTest {
            dispatcher.execute(proposal())
            dispatcher.execute(proposal("session:next"))
            assertEquals(2, executions)
        }

    @Test
    fun `conflicting reuse never overwrites the original receipt`() =
        runTest {
            val first = dispatcher.execute(proposal())
            try {
                dispatcher.execute(proposal(destination = "Ferry Building"))
                error("Expected conflict")
            } catch (_: ConflictingCallException) {
                assertEquals(first, repository.history().single())
                assertEquals(1, executions)
            }
        }

    @Test
    fun `invalid arguments and stale capabilities never reach the backend`() =
        runTest {
            val invalid =
                listOf(
                    proposal(destination = ""),
                    proposal(destination = "a".repeat(501)),
                    proposal(destination = "one\ntwo"),
                    proposal().copy(arguments = mapOf("destination" to "Park", "uri" to "intent://bad")),
                    proposal().copy(capabilityId = "unknown"),
                    proposal().copy(catalogRevision = "stale"),
                )
            invalid.forEachIndexed { index, request ->
                assertEquals(InvocationStatus.NOT_EXECUTED, dispatcher.execute(request.copy(callId = "invalid:$index")).status)
            }
            assertEquals(0, executions)
        }

    @Test
    fun `storage must commit dispatch before an external action`() =
        runTest {
            repository.failClaim = true
            try {
                dispatcher.execute(proposal())
                fail("Expected storage failure")
            } catch (_: IllegalStateException) {
                assertEquals(0, executions)
            }
            repository.failClaim = false
            repository.failDispatch = true
            try {
                dispatcher.execute(proposal())
                fail("Expected storage failure")
            } catch (_: IllegalStateException) {
                assertEquals(0, executions)
            }
        }

    @Test
    fun `unavailable foreground rejects without dispatch`() =
        runTest {
            unavailable = "Open EVA first"
            assertEquals(InvocationStatus.NOT_EXECUTED, dispatcher.execute(proposal()).status)
            assertEquals(0, executions)
        }

    @Test
    fun `arguments cannot change between validation and execution`() =
        runTest {
            val arguments = mutableMapOf("destination" to "Golden Gate Park")
            val changingBackend =
                object : ExecutionBackend {
                    override suspend fun unavailableReason(): String? {
                        arguments["destination"] = "A different target"
                        return null
                    }

                    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
                        assertEquals("Golden Gate Park", arguments.getValue("destination"))
                        return ExecutionOutcome(InvocationStatus.HANDED_OFF, "Opened")
                    }
                }
            val isolated = CapabilityDispatcher(CapabilityRegistry(mapOf(CapabilityRegistry.MAP_SEARCH to changingBackend)), repository)
            val result = isolated.execute(proposal().copy(arguments = arguments))
            assertEquals("Golden Gate Park", result.destination)
        }

    @Test
    fun `unexpected backend failure is uncertain and never automatically retried`() =
        runTest {
            action = { throw IllegalStateException("Transport disappeared") }
            assertEquals(InvocationStatus.UNKNOWN, dispatcher.execute(proposal()).status)
            assertEquals(InvocationStatus.UNKNOWN, dispatcher.execute(proposal()).status)
            assertEquals(1, executions)
        }

    @Test
    fun `cancellation after dispatch retains uncertainty`() =
        runTest {
            val started = CompletableDeferred<Unit>()
            action = {
                started.complete(Unit)
                CompletableDeferred<ExecutionOutcome>().await()
            }
            val job = launch { dispatcher.execute(proposal()) }
            started.await()
            job.cancelAndJoin()
            assertEquals(InvocationStatus.UNKNOWN, repository.history().single().status)
            dispatcher.execute(proposal())
            assertEquals(1, executions)
        }

    @Test
    fun `concurrent duplicate callers share one execution`() =
        runTest {
            val release = CompletableDeferred<Unit>()
            action = {
                release.await()
                ExecutionOutcome(InvocationStatus.HANDED_OFF, "Opened")
            }
            val first = async { dispatcher.execute(proposal()) }
            val second = async { dispatcher.execute(proposal()) }
            release.complete(Unit)
            assertEquals(first.await(), second.await())
            assertEquals(1, executions)
        }

    @Test
    fun `preflight failure records not executed without disabling future calls`() =
        runTest {
            val failing =
                object : ExecutionBackend by backend {
                    override suspend fun unavailableReason(): String? = error("Unavailable platform")
                }
            val isolated = CapabilityDispatcher(CapabilityRegistry(mapOf(CapabilityRegistry.MAP_SEARCH to failing)), repository)
            assertEquals(InvocationStatus.NOT_EXECUTED, isolated.execute(proposal()).status)
            assertEquals(0, executions)
        }

    @Test
    fun `cancellation during preflight resolves the claim without restart`() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val waiting =
                object : ExecutionBackend by backend {
                    override suspend fun unavailableReason(): String? {
                        entered.complete(Unit)
                        CompletableDeferred<Unit>().await()
                        return null
                    }
                }
            val isolated = CapabilityDispatcher(CapabilityRegistry(mapOf(CapabilityRegistry.MAP_SEARCH to waiting)), repository)
            val job = launch { isolated.execute(proposal()) }
            entered.await()
            job.cancelAndJoin()
            assertEquals(InvocationStatus.NOT_EXECUTED, isolated.execute(proposal()).status)
            assertEquals(0, executions)
        }

    @Test
    fun `cancellation during a committed state write cannot orphan the receipt`() =
        runTest {
            for (duringClaim in listOf(true, false)) {
                val memory = MemoryInvocationRepository()
                val written = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val delayed =
                    object : InvocationRepository by memory {
                        override suspend fun claim(record: InvocationRecord): ClaimResult {
                            val result = memory.claim(record)
                            if (duringClaim) {
                                written.complete(Unit)
                                release.await()
                            }
                            return result
                        }

                        override suspend fun transition(
                            callId: String,
                            expected: InvocationStatus,
                            status: InvocationStatus,
                            message: String,
                            data: JsonObject?,
                        ): InvocationRecord {
                            val result = memory.transition(callId, expected, status, message, data)
                            if (status == InvocationStatus.DISPATCHING) {
                                written.complete(Unit)
                                release.await()
                            }
                            return result
                        }
                    }
                val isolated = CapabilityDispatcher(CapabilityRegistry(mapOf(CapabilityRegistry.MAP_SEARCH to backend)), delayed)
                val job = launch { isolated.execute(proposal()) }
                written.await()
                job.cancel()
                release.complete(Unit)
                job.join()
                val expected = if (duringClaim) InvocationStatus.NOT_EXECUTED else InvocationStatus.UNKNOWN
                assertEquals(expected, isolated.execute(proposal()).status)
                assertEquals(0, executions)
            }
        }

    @Test
    fun `failure saving a handoff recovers to unknown without repeating the effect`() =
        runTest {
            repository.failOutcome = true
            try {
                dispatcher.execute(proposal())
                fail("Expected outcome write failure")
            } catch (_: IllegalStateException) {
                assertEquals(1, executions)
                assertEquals(InvocationStatus.DISPATCHING, repository.history().single().status)
            }
            repository.failOutcome = false
            repository.recoverInterrupted()
            assertEquals(InvocationStatus.UNKNOWN, dispatcher.execute(proposal()).status)
            assertEquals(1, executions)
        }
}
