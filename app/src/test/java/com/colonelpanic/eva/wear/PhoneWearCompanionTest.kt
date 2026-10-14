package com.colonelpanic.eva.wear

import android.Manifest
import android.content.Context
import com.colonelpanic.eva.EvaApplication
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = EvaApplication::class)
class PhoneWearCompanionTest {
    private val app: EvaApplication get() = RuntimeEnvironment.getApplication() as EvaApplication

    @Test fun `non assistant trigger returns actionable failure to watch without opening a session`() =
        runTest {
            app.wearGestureSettings.save(WearGestureConfiguration(enabled = true))
            val replies = mutableListOf<WatchStartResult>()
            var starts = 0
            val companion =
                PhoneWearCompanion(app, backgroundScope, assistantSelected = { false }, microphoneGranted = { true }, showSession = {
                    starts++
                    true
                }, deliver = {
                    _,
                    result,
                    ->
                    replies +=
                        result
                })
            val request = WatchStartRequest(UUID.randomUUID().toString(), System.currentTimeMillis())
            companion.start("watch", request)
            assertEquals(0, starts)
            assertEquals("failed", replies.single().status)
            assertEquals(request.id, replies.single().id)
            assertTrue(replies.single().message.contains("Default apps"))
        }

    @Test fun `unready assistant returns failure and duplicate delivery does not retry showSession`() =
        runTest {
            app.wearGestureSettings.save(WearGestureConfiguration(enabled = true))
            val replies = mutableListOf<WatchStartResult>()
            var starts = 0
            val companion =
                PhoneWearCompanion(app, backgroundScope, assistantSelected = { true }, microphoneGranted = { true }, showSession = {
                    starts++
                    false
                }, deliver = {
                    _,
                    result,
                    ->
                    replies +=
                        result
                })
            val request = WatchStartRequest(UUID.randomUUID().toString(), System.currentTimeMillis())
            companion.start("watch", request)
            runCurrent()
            companion.start("watch", request)
            assertEquals(1, starts)
            assertEquals(2, replies.size)
            assertTrue(replies.all { it.status == "failed" && it.message.contains("not ready") })
        }

    @Test fun `missing microphone permission never reaches assistant session`() =
        runTest {
            app.wearGestureSettings.save(WearGestureConfiguration(enabled = true))
            val replies = mutableListOf<WatchStartResult>()
            val companion =
                PhoneWearCompanion(app, backgroundScope, assistantSelected = {
                    true
                }, microphoneGranted = { false }, showSession = { throw AssertionError("must not launch") }, deliver = {
                    _,
                    result,
                    ->
                    replies +=
                        result
                })
            companion.start("watch", WatchStartRequest(UUID.randomUUID().toString(), System.currentTimeMillis()))
            assertEquals("failed", replies.single().status)
            assertTrue(replies.single().message.contains("microphone"))
        }

    @Test fun `recent reservations survive more than 128 other requests without replay`() =
        runTest {
            app.wearGestureSettings.save(WearGestureConfiguration(enabled = true))
            var selected = true
            var starts = 0
            val companion =
                PhoneWearCompanion(
                    app = app,
                    scope = backgroundScope,
                    assistantSelected = { selected },
                    microphoneGranted = { true },
                    showSession = {
                        starts++
                        false
                    },
                    deliver = { _, _ -> },
                )
            val original = WatchStartRequest(UUID.randomUUID().toString(), System.currentTimeMillis())
            companion.start("watch", original)
            runCurrent()
            selected = false
            repeat(140) { companion.start("watch", WatchStartRequest(UUID.randomUUID().toString(), System.currentTimeMillis())) }
            selected = true
            companion.start("watch", original)
            assertEquals(1, starts)
        }

    @Test fun `missing startup confirmation becomes unknown and late connection never retries start`() =
        runTest {
            app.wearGestureSettings.save(WearGestureConfiguration(enabled = true))
            val replies = mutableListOf<WatchStartResult>()
            var starts = 0
            val companion =
                PhoneWearCompanion(
                    app = app,
                    scope = backgroundScope,
                    assistantSelected = { true },
                    microphoneGranted = { true },
                    showSession = {
                        starts++
                        true
                    },
                    deliver = { _, result -> replies += result },
                )
            val request = WatchStartRequest(UUID.randomUUID().toString(), System.currentTimeMillis())
            companion.start("watch", request)
            runCurrent()
            advanceTimeBy(30_001)
            runCurrent()
            companion.start("watch", request)
            companion.onTrace(
                com.colonelpanic.eva.diagnostics.TraceEvent(
                    0,
                    com.colonelpanic.eva.diagnostics.TraceLevel.INFO,
                    "wear.session.connected",
                    mapOf("request" to request.id),
                ),
            )
            runCurrent()
            assertEquals(1, starts)
            assertEquals(listOf("requested", "unknown", "unknown", "connected"), replies.map { it.status })
        }
}
