package com.colonelpanic.eva.conversation

import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationRecord
import com.colonelpanic.eva.capability.InvocationRepository
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.MemoryInvocationRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionControllerTest {
    private val repository = MemoryInvocationRepository()
    private var calls = 0
    private val backend =
        object : ExecutionBackend {
            override suspend fun unavailableReason(): String? = null

            override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
                calls++
                return ExecutionOutcome(InvocationStatus.HANDED_OFF, "Opened map search")
            }
        }

    @Test
    fun `typed command reaches dispatcher and displays its actual result`() =
        runTest {
            val controller =
                SessionController(
                    LocalCommandProvider(),
                    CapabilityDispatcher(
                        CapabilityRegistry(
                            mapOf(CapabilityRegistry.MAP_SEARCH to backend),
                        ),
                        repository,
                    ),
                    repository,
                    this,
                )
            advanceUntilIdle()
            controller.submit("map Golden Gate Park")
            controller.submit("map duplicate click")
            advanceUntilIdle()
            val entry =
                controller.state.value.entries
                    .single()
            assertEquals("Golden Gate Park", entry.destination)
            assertEquals(EntryStatus.HANDED_OFF, entry.status)
            assertEquals(1, calls)
            assertFalse(controller.state.value.isSubmitting)
        }

    @Test
    fun `unsupported input is explained without calling a backend`() =
        runTest {
            val controller =
                SessionController(
                    LocalCommandProvider(),
                    CapabilityDispatcher(
                        CapabilityRegistry(
                            mapOf(CapabilityRegistry.MAP_SEARCH to backend),
                        ),
                        repository,
                    ),
                    repository,
                    this,
                )
            advanceUntilIdle()
            controller.submit("send that agent a message")
            advanceUntilIdle()
            assertEquals(
                EntryStatus.NOT_EXECUTED,
                controller.state.value.entries
                    .single()
                    .status,
            )
            assertEquals(0, calls)
        }

    @Test
    fun `journal startup failure disables all submissions`() =
        runTest {
            val broken =
                object : InvocationRepository by repository {
                    override suspend fun recoverInterrupted() {
                        error("Unavailable storage")
                    }
                }
            val controller =
                SessionController(
                    LocalCommandProvider(),
                    CapabilityDispatcher(
                        CapabilityRegistry(
                            mapOf(CapabilityRegistry.MAP_SEARCH to backend),
                        ),
                        broken,
                    ),
                    broken,
                    this,
                )
            advanceUntilIdle()
            controller.submit("map Park")
            advanceUntilIdle()
            assertNotNull(controller.state.value.errorMessage)
            assertEquals(0, calls)
            assertEquals(0, controller.state.value.entries.size)
        }

    @Test
    fun `malformed provider proposal is a rejection rather than a storage outage`() =
        runTest {
            val invalid =
                object : TypedInputProvider {
                    override fun propose(
                        callId: String,
                        input: String,
                        catalogRevision: String,
                    ) = LocalCommandProvider().propose(callId, input, catalogRevision)?.copy(callId = "")
                }
            val controller =
                SessionController(
                    invalid,
                    CapabilityDispatcher(
                        CapabilityRegistry(
                            mapOf(CapabilityRegistry.MAP_SEARCH to backend),
                        ),
                        repository,
                    ),
                    repository,
                    this,
                )
            advanceUntilIdle()
            controller.submit("map Park")
            advanceUntilIdle()
            assertEquals(
                EntryStatus.NOT_EXECUTED,
                controller.state.value.entries
                    .single()
                    .status,
            )
            assertNull(controller.state.value.errorMessage)
            assertFalse(controller.state.value.isSubmitting)
            assertEquals(0, calls)
        }

    @Test
    fun `outcome storage failure disables later submissions and displays uncertainty`() =
        runTest {
            val controller =
                SessionController(
                    LocalCommandProvider(),
                    CapabilityDispatcher(
                        CapabilityRegistry(
                            mapOf(CapabilityRegistry.MAP_SEARCH to backend),
                        ),
                        repository,
                    ),
                    repository,
                    this,
                )
            advanceUntilIdle()
            repository.failOutcome = true
            controller.submit("map Park")
            advanceUntilIdle()
            assertEquals(
                EntryStatus.UNKNOWN,
                controller.state.value.entries
                    .single()
                    .status,
            )
            assertNotNull(controller.state.value.errorMessage)
            controller.submit("map Ferry Building")
            advanceUntilIdle()
            assertEquals(1, calls)
        }

    @Test
    fun `claim failure reports no execution and still blocks unsafe submissions`() =
        runTest {
            val controller =
                SessionController(
                    LocalCommandProvider(),
                    CapabilityDispatcher(
                        CapabilityRegistry(
                            mapOf(CapabilityRegistry.MAP_SEARCH to backend),
                        ),
                        repository,
                    ),
                    repository,
                    this,
                )
            advanceUntilIdle()
            repository.failClaim = true
            controller.submit("map Park")
            advanceUntilIdle()
            assertEquals(
                EntryStatus.NOT_EXECUTED,
                controller.state.value.entries
                    .single()
                    .status,
            )
            assertNotNull(controller.state.value.errorMessage)
            assertFalse(controller.state.value.isSubmitting)
            assertEquals(0, calls)
        }

    @Test
    fun `startup restores uncertain destination without executing again`() =
        runTest {
            repository.claim(
                InvocationRecord(
                    "prior",
                    "fingerprint",
                    "map Park",
                    "Park",
                    InvocationStatus.DISPATCHING,
                    "Opening",
                    1L,
                    CapabilityRegistry.MAP_SEARCH,
                    "legacy-revision",
                ),
            )
            val controller =
                SessionController(
                    LocalCommandProvider(),
                    CapabilityDispatcher(
                        CapabilityRegistry(
                            mapOf(CapabilityRegistry.MAP_SEARCH to backend),
                        ),
                        repository,
                    ),
                    repository,
                    this,
                )
            advanceUntilIdle()
            val entry =
                controller.state.value.entries
                    .single()
            assertEquals(EntryStatus.UNKNOWN, entry.status)
            assertEquals("Park", entry.destination)
            assertFalse(controller.state.value.isLoading)
            assertEquals(0, calls)
        }
}
