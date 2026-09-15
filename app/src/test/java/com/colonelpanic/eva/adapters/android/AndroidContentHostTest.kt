package com.colonelpanic.eva.adapters.android

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ApplicationInfo
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.Looper
import com.colonelpanic.eva.adapters.declarative.BindingArguments
import com.colonelpanic.eva.adapters.declarative.BindingResults
import com.colonelpanic.eva.adapters.declarative.ContentQueryFailure
import com.colonelpanic.eva.adapters.declarative.DeclarativeBackend
import com.colonelpanic.eva.adapters.declarative.DeclarativeBinding
import com.colonelpanic.eva.adapters.declarative.PackageHttpClient
import com.colonelpanic.eva.adapters.declarative.Predicate
import com.colonelpanic.eva.adapters.declarative.ScalarSlot
import com.colonelpanic.eva.adapters.declarative.contentFixture
import com.colonelpanic.eva.capability.InteractionMode
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.ToolProposal
import com.colonelpanic.eva.capability.WaitBudget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AndroidContentHostTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val capability = contentFixture().capabilities.first()
    private val binding =
        (capability.binding as DeclarativeBinding.Content).copy(
            projection =
                linkedMapOf(
                    "id" to "string",
                    "name" to "string",
                ),
        )
    private lateinit var provider: FakeProvider

    private fun host(clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }) =
        AndroidDeclarativeHost(AndroidIntentHost(), PackageHttpClient(credential = { _, _ -> null }), app, clock)

    @Before
    fun register() {
        val info =
            ProviderInfo().apply {
                authority = "sh.paseo.assistant"
                packageName = "sh.paseo"
                name = FakeProvider::class.java.name
                exported = true
                enabled = true
                applicationInfo =
                    ApplicationInfo().apply {
                        packageName = "sh.paseo"
                        enabled = true
                    }
            }
        shadowOf(app.packageManager).addOrUpdateProvider(info)
        provider = FakeProvider()
        provider.attachInfo(app, info)
        ShadowContentResolver.registerProviderInternal(info.authority, provider)
    }

    @Test
    fun `resolver receives only declared projection encoded query and bound selection off main thread`() =
        runBlocking {
            val selected = binding.copy(selection = listOf(Predicate("name", "=", ScalarSlot.Argument("q", "string"))))
            val input = "x' OR 1=1 -- &other=/#😀"
            val request = BindingArguments(capability, mapOf("q" to input, "limit" to "2")).content(selected)
            val rows = host().query(request, 30_000)
            assertFalse(provider.onMain)
            assertArrayEquals(arrayOf("id", "name"), provider.projection)
            assertEquals("name = ?", provider.selection)
            assertArrayEquals(arrayOf(input), provider.arguments)
            assertEquals(input, provider.uri!!.getQueryParameter("q"))
            assertEquals("2", provider.uri!!.getQueryParameter("limit"))
            assertNull(provider.uri!!.getQueryParameter("other"))
            assertNull(provider.sort)
            assertTrue(provider.cursor!!.isClosed)
            assertEquals(2, rows.rows.size)
            assertFalse(rows.truncated)
            assertFalse(rows.rows.any { "secret" in it })
            val result = BindingResults.content(selected, rows)
            assertEquals(InvocationStatus.COMPLETED, result.status)
            assertEquals(JsonArray(rows.rows), result.data!!["rows"])
        }

    @Test
    fun `row and UTF8 byte limits omit whole rows and flag only actual truncation`() =
        runBlocking {
            val request = BindingArguments(capability, emptyMap()).content(binding)
            val one = host().query(request.copy(maxRows = 1), 30_000)
            assertNull(provider.selection)
            assertNull(provider.arguments)
            assertEquals(1, one.rows.size)
            assertTrue(one.truncated)
            assertTrue(provider.cursor!!.isClosed)
            val rowBytes = JsonArray(one.rows).toString().toByteArray(Charsets.UTF_8).size
            val exact = host().query(request.copy(maxBytes = rowBytes), 30_000)
            assertEquals(one, exact)
            val none = host().query(request.copy(maxBytes = rowBytes - 1), 30_000)
            assertTrue(none.rows.isEmpty())
            assertTrue(none.truncated)
            val result = BindingResults.content(binding.copy(maxBytes = rowBytes - 1), one)
            assertEquals(JsonArray(emptyList()), result.data!!["rows"])
            assertFalse(result.message.contains("id-1"))
            provider.values = provider.values.take(1)
            assertFalse(host().query(request.copy(maxRows = 1, maxBytes = rowBytes), 30_000).truncated)
            provider.values = emptyList()
            assertFalse(host().query(request.copy(maxBytes = 1), 30_000).truncated)
        }

    @Test
    fun `scalar cursor types and null values survive without coercing blobs or bad booleans`() =
        runBlocking {
            provider.columns = arrayOf("id", "count", "score", "done", "optional", "secret")
            provider.values = listOf(arrayOf("id", 7L, 1.5, 1L, null, "hidden"))
            val typed =
                binding.copy(
                    projection =
                        linkedMapOf(
                            "id" to "string",
                            "count" to "integer",
                            "score" to "number",
                            "done" to "boolean",
                            "optional" to "string",
                        ),
                )
            val rows = host().query(BindingArguments(capability, emptyMap()).content(typed), 30_000)
            assertEquals(
                JsonObject(
                    mapOf(
                        "id" to JsonPrimitive("id"),
                        "count" to JsonPrimitive(7),
                        "score" to JsonPrimitive(1.5),
                        "done" to JsonPrimitive(true),
                        "optional" to JsonNull,
                    ),
                ),
                rows.rows.single(),
            )
            for (bad in listOf(2L, "true", byteArrayOf(1))) {
                provider.values = listOf(arrayOf("id", 7L, 1.5, bad, null, "hidden"))
                val result = execute(typed)
                assertEquals(InvocationStatus.FAILED, result.status)
                assertNull(result.data)
                assertTrue(provider.cursor!!.isClosed)
            }
        }

    @Test
    fun `provider denial missing provider null cursor and missing columns give honest envelopes`() =
        runBlocking {
            provider.failure = SecurityException("PRIVATE details")
            val denied = execute()
            assertEquals(InvocationStatus.NOT_EXECUTED, denied.status)
            assertTrue(denied.message.contains("unauthorized_caller"))
            assertFalse(denied.message.contains("PRIVATE"))
            assertNull(denied.data)
            provider.failure = null
            provider.columns = arrayOf("id", "secret")
            provider.values = listOf(arrayOf("id-1", "hidden"))
            val missingColumn = execute()
            assertEquals(InvocationStatus.FAILED, missingColumn.status)
            assertNull(missingColumn.data)
            assertTrue(provider.cursor!!.isClosed)
            provider.nullCursor = true
            assertEquals(InvocationStatus.FAILED, execute().status)
            val absent = binding.copy(authority = "missing.provider", uri = "content://missing.provider/items")
            val calls = provider.calls
            assertTrue(host().unavailableReason(absent)!!.contains("Install or enable"))
            assertEquals(InvocationStatus.NOT_EXECUTED, execute(absent).status)
            assertEquals(calls, provider.calls)
        }

    @Test
    fun `malformed later rows discard earlier rows and close the cursor`() =
        runBlocking {
            provider.values = listOf(arrayOf("id-1", "valid", "secret"), arrayOf("id-2", 5L, "secret"))
            val result = execute()
            assertEquals(InvocationStatus.FAILED, result.status)
            assertNull(result.data)
            assertFalse(result.message.contains("id-1"))
            assertTrue(provider.cursor!!.isClosed)
        }

    @Test
    fun `expired deadline refuses submission and expiry during cursor traversal discards rows`() =
        runBlocking {
            val request = BindingArguments(capability, emptyMap()).content(binding)
            val expired = runCatching { host().query(request, 0) }.exceptionOrNull() as ContentQueryFailure
            assertEquals(InvocationStatus.NOT_EXECUTED, expired.outcome.status)
            assertEquals(0, provider.calls)
            val clock = AtomicLong(0)
            provider.onMove = { clock.set(30_001) }
            val late = runCatching { host(clock::get).query(request, 30_000) }.exceptionOrNull() as ContentQueryFailure
            assertEquals(InvocationStatus.UNKNOWN, late.outcome.status)
            assertTrue(late.outcome.message.contains("deadline_exceeded"))
            assertNull(late.outcome.data)
            assertTrue(provider.cursor!!.isClosed)
        }

    @Test
    fun `coroutine cancellation signals provider and closes its late cursor`() =
        runBlocking {
            val entered = CountDownLatch(1)
            val cancelled = CountDownLatch(1)
            val closed = CountDownLatch(1)
            provider.block = { signal ->
                signal!!.setOnCancelListener { cancelled.countDown() }
                entered.countDown()
                check(cancelled.await(5, TimeUnit.SECONDS))
            }
            provider.onClose = { closed.countDown() }
            val pending = async(Dispatchers.Default) { host().query(BindingArguments(capability, emptyMap()).content(binding), 30_000) }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                pending.cancelAndJoin()
                assertTrue(cancelled.await(5, TimeUnit.SECONDS))
                assertTrue(closed.await(5, TimeUnit.SECONDS))
            } finally {
                cancelled.countDown()
                pending.cancelAndJoin()
            }
        }

    private suspend fun execute(selected: DeclarativeBinding.Content = binding) =
        DeclarativeBackend(capability.copy(binding = selected), host()) { WaitBudget(InteractionMode.TYPED, 30_000, null, null) }
            .execute(ToolProposal("call", "content", emptyMap(), "read", "revision"))

    class FakeProvider : ContentProvider() {
        var columns = arrayOf("id", "name", "secret")
        var values: List<Array<Any?>> = listOf(arrayOf("id-1", "😀 café", "hidden"), arrayOf("id-2", "Second", "hidden"))
        var failure: RuntimeException? = null
        var nullCursor = false
        var projection: Array<out String>? = null
        var selection: String? = null
        var arguments: Array<out String>? = null
        var uri: Uri? = null
        var sort: String? = null
        var onMain = true
        var calls = 0
        var cursor: Cursor? = null
        var onMove: () -> Unit = {}
        var onClose: () -> Unit = {}
        var block: ((CancellationSignal?) -> Unit)? = null

        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
            cancellationSignal: CancellationSignal?,
        ): Cursor? {
            block?.invoke(cancellationSignal)
            return query(uri, projection, selection, selectionArgs, sortOrder)
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? {
            calls++
            onMain = Looper.myLooper() == Looper.getMainLooper()
            this.uri = uri
            this.projection = projection
            this.selection = selection
            arguments = selectionArgs
            sort = sortOrder
            failure?.let { throw it }
            if (nullCursor) return null
            return object : MatrixCursor(columns) {
                override fun onMove(
                    oldPosition: Int,
                    newPosition: Int,
                ): Boolean {
                    onMove()
                    return true
                }

                override fun close() {
                    super.close()
                    onClose()
                }
            }.apply {
                values.forEach { addRow(it) }
                cursor = this
            }
        }

        override fun getType(uri: Uri): String? = null

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = error("Read only")

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = error("Read only")

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = error("Read only")
    }
}
