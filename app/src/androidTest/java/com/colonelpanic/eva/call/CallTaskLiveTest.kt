package com.colonelpanic.eva.call

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.colonelpanic.eva.EvaApplication
import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.ToolProposal
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Places a real call task through EVA's own coordinator, registry and call brain, without its UI, in
 * a new "Live call task test" thread, and logs the transcript under `EvaCallTaskLive`. Opt in with
 * `evaCallTaskNumber` and `evaCallTaskGoal`; `evaCallTaskBriefing`, `evaCallTaskDisclosures` and
 * `evaCallTaskMinutes` (default 4, after which the task is cancelled and hung up) are optional.
 */
@RunWith(AndroidJUnit4::class)
class CallTaskLiveTest {
    @Test
    fun runsACallTask() =
        runBlocking {
            val arguments = InstrumentationRegistry.getArguments()
            val number = arguments.getString("evaCallTaskNumber")
            val goal = arguments.getString("evaCallTaskGoal")
            assumeTrue("Requires evaCallTaskNumber and evaCallTaskGoal", !number.isNullOrBlank() && !goal.isNullOrBlank())
            val minutes = (arguments.getString("evaCallTaskMinutes") ?: "4").toLong()
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EvaApplication
            val thread = app.conversations.createThread("Live call task test")
            var taskId = ""
            val proposal = { revision: String ->
                taskId = "live-call-${UUID.randomUUID()}"
                ToolProposal(
                    taskId,
                    CallCapabilities.START,
                    mapOf(
                        "number" to requireNotNull(number),
                        "goal" to requireNotNull(goal),
                        "briefing" to (arguments.getString("evaCallTaskBriefing") ?: "Owner-approved test call."),
                        "disclosures" to (arguments.getString("evaCallTaskDisclosures") ?: "Only what the goal states."),
                        "mandate" to "[]",
                    ) + listOfNotNull(arguments.getString("evaCallTaskProvider")?.let { "provider" to it }),
                    "Live call task test",
                    revision,
                    thread.id,
                    "live-call-turn-${UUID.randomUUID()}",
                )
            }
            val dispatcher = CapabilityDispatcher(app.registry, app.invocations)
            val outcome =
                withTimeoutOrNull(minutes * 60_000) {
                    // A freshly started process is still assembling its catalog, and a proposal against an older
                    // revision is refused before anything is dialed.
                    var result = dispatcher.execute(proposal(settledRevision(app)))
                    repeat(2) {
                        if (result.message == CapabilityRegistry.STALE_MESSAGE) result = dispatcher.execute(proposal(settledRevision(app)))
                    }
                    result
                }
            if (outcome == null) Log.i(TAG, "cancel: ${app.callTasks.cancel(taskId).message}")
            val task = withTimeoutOrNull(30_000) { app.callTasks.tasks.first { it[taskId]?.state?.terminal == true } }?.get(taskId)
            Log.i(TAG, "outcome=${outcome?.status} ${outcome?.message}")
            task?.transcript?.forEach { Log.i(TAG, "${it.speaker}: ${it.text}") }
            Log.i(TAG, "final=${task?.state} leg=${task?.legState}")
            assertNotNull("The call task never reached a terminal state; check the phone for a call still in progress.", task)
        }

    private suspend fun settledRevision(app: EvaApplication): String {
        var revision = ""
        var stableSince = System.currentTimeMillis()
        val deadline = stableSince + 30_000
        while (System.currentTimeMillis() < deadline) {
            val snapshot = app.registry.snapshot
            if (snapshot.revision != revision || CallCapabilities.START !in snapshot.definitions) {
                revision = snapshot.revision
                stableSince = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - stableSince >= 2_000) {
                return revision
            }
            delay(250)
        }
        return revision
    }

    private companion object {
        const val TAG = "EvaCallTaskLive"
    }
}
