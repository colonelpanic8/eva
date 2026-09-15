package com.colonelpanic.eva.conversation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.colonelpanic.eva.capability.CapabilityDefinition
import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.conversation.prompt.PromptComponent
import com.colonelpanic.eva.conversation.prompt.PromptConfig
import com.colonelpanic.eva.data.JournalDatabase
import com.colonelpanic.eva.data.SqliteConversationStore
import com.colonelpanic.eva.data.SqliteInvocationRepository
import com.colonelpanic.eva.providers.openai.ChatGptTokens
import com.colonelpanic.eva.providers.openai.OpenAiResponsesProvider
import com.colonelpanic.eva.providers.openai.SubscriptionAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Opt-in live check of the thread model against the real Responses backend. Supply a ChatGPT
 * access token with `-Pandroid.testInstrumentationRunnerArguments.evaAccessToken=…` (and
 * `evaAccountId`); without one the tests skip. Nothing here touches the phone: the single
 * capability is a stub that answers from memory.
 */
@RunWith(AndroidJUnit4::class)
class ThreadLiveTest {
    private val lookups = AtomicInteger()

    private val lookup =
        CapabilityDefinition(
            "test.fact",
            "Look up a stored fact",
            "Look up a fact EVA has stored. Pass the subject to look up. This is the only way to learn the fact.",
            Json
                .parseToJsonElement(
                    """{"type":"object","properties":{"subject":{"type":"string","minLength":1}},
                    "required":["subject"],"additionalProperties":false}""",
                ).jsonObject,
            readOnly = true,
        )

    private val registry =
        CapabilityRegistry(
            mapOf(
                lookup.id to
                    object : ExecutionBackend {
                        override suspend fun unavailableReason(): String? = null

                        override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
                            lookups.incrementAndGet()
                            return ExecutionOutcome(InvocationStatus.COMPLETED, "The stored fact is: the courier's name is Wren.")
                        }
                    },
            ),
            listOf(lookup),
        )

    private val prompt =
        PromptConfig(
            listOf(
                PromptComponent(
                    id = "live-test",
                    instruction =
                        "You are EVA, under test. When the user asks about a stored fact, call the supplied tool " +
                            "to look it up, then state the answer in one short sentence.",
                ),
            ),
        )

    private fun tokens(): Pair<String, String?>? {
        val args = InstrumentationRegistry.getArguments()
        val token = args.getString("evaAccessToken") ?: return null
        return token to args.getString("evaAccountId")
    }

    private fun <T> live(body: suspend Fixture.() -> T) {
        val credentials = tokens()
        assumeTrue("Requires a ChatGPT access token in evaAccessToken", credentials != null)
        val (token, accountId) = checkNotNull(credentials)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "live-${UUID.randomUUID()}.db"
        val journal = JournalDatabase(context, name)
        try {
            runBlocking(Dispatchers.Main.immediate) {
                val repository = SqliteInvocationRepository(journal)
                val access =
                    SubscriptionAccess(
                        { ChatGptTokens("", token, "", accountId, null, null, 0L) },
                        "1.0.0",
                    )
                val store = SqliteConversationStore(journal)
                val controller =
                    ThreadController(
                        registry = registry,
                        dispatcher = CapabilityDispatcher(registry, repository),
                        repository = repository,
                        store = store,
                        scope = this,
                        providerFactory = { OpenAiResponsesProvider(access, MODEL) },
                        prompt = { prompt },
                    )
                controller.state.first { !it.isLoading }
                try {
                    Fixture(controller, store).body()
                } finally {
                    controller.disconnect()
                }
            }
        } finally {
            journal.close()
            context.deleteDatabase(name)
        }
    }

    private class Fixture(
        val controller: ThreadController,
        val store: ConversationStore,
    ) {
        suspend fun turn(): Turn = store.turns(checkNotNull(controller.state.value.threadId)).last()

        /** The turn is the unit that finishes, and it leaves OPEN only when its answer is recorded. */
        suspend fun awaitAnswer(): Turn =
            withTimeout(TIMEOUT_MILLIS) {
                while (true) {
                    val turns =
                        controller.state.value.threadId
                            ?.let { store.turns(it) }
                            .orEmpty()
                    turns.lastOrNull()?.takeIf { it.status != TurnStatus.OPEN }?.let { return@withTimeout it }
                    delay(POLL_MILLIS)
                }
                error("unreachable")
            }
    }

    @Test
    fun aTypedTurnIsAnsweredByTheRealModel() =
        live {
            controller.connect("")
            withTimeout(TIMEOUT_MILLIS) { controller.state.first { it.providerStatus == ProviderStatus.CONNECTED } }
            controller.submit("In one short sentence: what is the capital of France?")
            val answered = awaitAnswer()
            assertEquals(TurnStatus.ANSWERED, answered.status)
            val answer =
                controller.state.value.entries
                    .first { it.id == answered.id }
                    .response
            assertTrue("The model said: $answer", answer.contains("Paris", ignoreCase = true))
        }

    @Test
    fun aTurnFinishesOnAnotherLegAfterTheCallEnds() =
        live {
            controller.connect("")
            withTimeout(TIMEOUT_MILLIS) { controller.state.first { it.providerStatus == ProviderStatus.CONNECTED } }
            controller.submit("Look up the stored fact about the courier and tell me the name.")

            // The tool call is journaled under the turn; ending the attachment here is the hang-up.
            withTimeout(TIMEOUT_MILLIS) { controller.state.first { state -> state.entries.any { it.capabilityId == lookup.id } } }
            controller.disconnect()
            assertEquals(ProviderStatus.DISCONNECTED, controller.state.value.providerStatus)

            val answered = awaitAnswer()
            assertEquals(TurnStatus.ANSWERED, answered.status)
            val entries = controller.state.value.entries
            assertTrue(
                "No re-home notice: ${entries.map { it.response }}",
                entries.any { it.response.contains("Continuing after the call") },
            )
            val answer = entries.first { it.id == answered.id }.response
            assertTrue("The model said: $answer", answer.contains("Wren", ignoreCase = true))
            assertEquals(1, lookups.get())
        }

    private companion object {
        const val MODEL = "gpt-5.6-sol"
        const val TIMEOUT_MILLIS = 120_000L
        const val POLL_MILLIS = 250L
    }
}
