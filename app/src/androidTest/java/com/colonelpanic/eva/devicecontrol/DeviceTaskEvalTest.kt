package com.colonelpanic.eva.devicecontrol

import android.app.UiAutomation
import android.os.Build
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.colonelpanic.eva.EvaApplication
import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.ToolProposal
import com.colonelpanic.eva.data.SqliteInvocationRepository
import com.colonelpanic.eva.devicecontrol.worker.WorkerModel
import com.colonelpanic.eva.devicecontrol.worker.WorkerReply
import com.colonelpanic.eva.devicecontrol.worker.WorkerRequest
import com.colonelpanic.eva.providers.openai.ChatGptTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Live opt-in task acceptance, with runtime credentials over an emulator-only adb reverse. */
@RunWith(AndroidJUnit4::class)
class DeviceTaskEvalTest {
    @Test fun admittedTaskReachesTerminalReceipt(): Unit =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue(args.getString("evaDeviceEval") == "true")
            assumeTrue(Build.MODEL.startsWith("sdk_gphone"))
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            val app = instrumentation.targetContext.applicationContext as EvaApplication
            app.configuration.awaitReady()
            app.extensions.awaitReady()
            val port = checkNotNull(args.getString("credentialPort")).toInt()
            val credentials =
                OkHttpClient().newCall(Request.Builder().url("http://127.0.0.1:$port/credentials").build()).execute().use {
                    check(it.isSuccessful)
                    Json.parseToJsonElement(it.body.string()).jsonObject
                }
            val tokens = credentials.getValue("tokens").jsonObject

            fun token(name: String) = tokens[name]?.jsonPrimitive?.content.orEmpty()
            app.chatGpt.save(
                ChatGptTokens(
                    token("id_token"),
                    token("access_token"),
                    token("refresh_token"),
                    token("account_id"),
                    null,
                    null,
                    System.currentTimeMillis() + 3_600_000,
                ),
            )
            app.capabilities.savePortalToken(credentials.getValue("portal").jsonPrimitive.content)
            var requests = 0
            val coordinator =
                DeviceTaskCoordinator {
                    app.createDeviceTaskAgent(onDeviceTiming = { timing ->
                        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "EVA_ACTION $timing\n") })
                    }) { model ->
                        object : WorkerModel {
                            override fun close() = model.close()

                            override suspend fun complete(request: WorkerRequest): WorkerReply {
                                requests++
                                if (args.getString("dumpModel") == "true" &&
                                    requests <= (args.getString("dumpModelSteps")?.toIntOrNull()?.coerceIn(1, 30) ?: 3)
                                ) {
                                    java.io.File(app.filesDir, "device-eval-request-$requests.json").writeText(
                                        Json { prettyPrint = true }.encodeToString(request),
                                    )
                                }
                                val reply = model.complete(request)
                                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "EVA_USAGE ${reply.usage}\n") })
                                return reply
                            }
                        }
                    }
                }
            val registry = CapabilityRegistry(mapOf(CapabilityRegistry.DEVICE_TASK to coordinator))
            val goal = checkNotNull(args.getString("goal"))
            val id = UUID.randomUUID().toString()
            val reporter =
                launch(Dispatchers.Default) {
                    coordinator.running.collect { running ->
                        running?.progress?.let { p ->
                            instrumentation.sendStatus(
                                0,
                                Bundle().apply {
                                    putString("stream", "EVA_STEP ${p.step} ${p.phase} ${p.message} ${p.timing}\n")
                                },
                            )
                        }
                    }
                }
            val watchdog =
                launch(Dispatchers.Default) {
                    delay(180_000)
                    coordinator.stop()
                }
            val interrupt =
                args.getString("interruptAt")?.let { phase ->
                    launch(Dispatchers.Default) {
                        coordinator.running.first { it?.progress?.phase?.name == phase }
                        delay(100)
                        val start = System.nanoTime()
                        coordinator.stop()
                        instrumentation.sendStatus(
                            0,
                            Bundle().apply { putString("stream", "EVA_STOP $phase latchMicros=${(System.nanoTime() - start) / 1000}\n") },
                        )
                    }
                }
            SqliteInvocationRepository(app, "device-eval.sqlite").use { repository ->
                val dispatcher =
                    CapabilityDispatcher(registry, repository, executeAdmitted = {
                        proposal,
                        backend,
                        ->
                        coordinator.executeAdmitted(proposal, backend, true)
                    })
                val result =
                    dispatcher.execute(
                        ToolProposal(
                            id,
                            CapabilityRegistry.DEVICE_TASK,
                            mapOf("goal" to goal),
                            goal,
                            registry.snapshot.revision,
                            "eval",
                            id,
                        ),
                    )
                instrumentation.sendStatus(
                    0,
                    Bundle().apply {
                        putString(
                            "stream",
                            "EVA_DEVICE_EVAL ${args.getString("case")} ${result.status} ${result.message}\n${result.data}\n",
                        )
                    },
                )
                reporter.cancelAndJoin()
                watchdog.cancelAndJoin()
                interrupt?.cancelAndJoin()
                if (args.getString("interruptAt") == null) {
                    assertEquals(InvocationStatus.COMPLETED, result.status)
                } else {
                    assertTrue(result.status in setOf(InvocationStatus.NOT_EXECUTED, InvocationStatus.FAILED, InvocationStatus.UNKNOWN))
                }
                args.getString("answerContains")?.let { assertTrue(result.message.contains(it, ignoreCase = true)) }
                Unit
            }
        }
}
