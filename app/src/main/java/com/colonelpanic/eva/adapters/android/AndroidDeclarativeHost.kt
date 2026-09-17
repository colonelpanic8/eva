package com.colonelpanic.eva.adapters.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.net.toUri
import com.colonelpanic.eva.adapters.declarative.ContentQueryFailure
import com.colonelpanic.eva.adapters.declarative.ContentRequest
import com.colonelpanic.eva.adapters.declarative.ContentRowBudget
import com.colonelpanic.eva.adapters.declarative.ContentRows
import com.colonelpanic.eva.adapters.declarative.DeclarativeBinding
import com.colonelpanic.eva.adapters.declarative.DeclarativeHost
import com.colonelpanic.eva.adapters.declarative.HttpRequest
import com.colonelpanic.eva.adapters.declarative.HttpResponse
import com.colonelpanic.eva.adapters.declarative.IntentRequest
import com.colonelpanic.eva.adapters.declarative.PackageHttpClient
import com.colonelpanic.eva.capability.BoundedJson
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resumeWithException

class AndroidDeclarativeHost(
    private val intents: AndroidIntentHost,
    private val http: PackageHttpClient,
    private val context: Context,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) : DeclarativeHost {
    override suspend fun unavailableReason(binding: DeclarativeBinding): String? =
        when (binding) {
            is DeclarativeBinding.Intent -> intents.unavailableReason()
            is DeclarativeBinding.Content -> ContentProviderAccess.inspect(context, binding.authority).problem
            is DeclarativeBinding.Select -> unavailableReason(binding.present) ?: unavailableReason(binding.absent)
            is DeclarativeBinding.Http -> null
        }

    override suspend fun launch(request: IntentRequest): ExecutionOutcome {
        if (request.appName != null) return ExecutionOutcome(InvocationStatus.NOT_EXECUTED, "App-name handoffs are not available yet.")
        return intents.launch(
            buildIntent(request),
            request.receipts.success ?: "Request handed to the app. Completion is not verified.",
            if (request.targetClass != null) {
                "The activity ${request.targetPackage}/${request.targetClass} is unavailable. Install or enable the target app, or update this package for its installed version."
            } else {
                request.receipts.handlerMissing ?: "No installed app can handle this request."
            },
        )
    }

    override suspend fun query(
        request: ContentRequest,
        timeoutMillis: Long,
    ): ContentRows {
        if (timeoutMillis <=
            0
        ) {
            throw contentFailure(InvocationStatus.NOT_EXECUTED, "deadline_exceeded: The query deadline expired before submission.")
        }
        val deadline = elapsedRealtime() + timeoutMillis.coerceAtMost(60_000)
        val signal = CancellationSignal()
        return suspendCancellableCoroutine { continuation ->
            val finished = AtomicBoolean(false)
            val submitted = AtomicBoolean(false)

            fun cancelQuery() {
                try {
                    cancellations.execute { signal.cancel() }
                } catch (_: RejectedExecutionException) {
                    // All cancellation workers are waiting on unresponsive providers.
                }
            }
            val timer =
                deadlines.schedule({
                    val failure =
                        synchronized(finished) {
                            if (finished.compareAndSet(false, true)) deadlineFailure(submitted.get()) else null
                        }
                    if (failure != null) {
                        continuation.resumeWithException(failure)
                        cancelQuery()
                    }
                }, (deadline - elapsedRealtime()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            continuation.invokeOnCancellation {
                finished.set(true)
                timer.cancel(false)
                cancelQuery()
            }
            try {
                queries.execute {
                    val result =
                        runCatching {
                            fun checkDeadline() {
                                if (finished.get() || elapsedRealtime() >= deadline) throw deadlineFailure(submitted.get())
                            }
                            checkDeadline()
                            val uri = request.uri.toUri()
                            ContentProviderAccess.inspect(context, requireNotNull(uri.authority)).problem?.let {
                                throw contentFailure(InvocationStatus.NOT_EXECUTED, "not_configured: $it")
                            }
                            synchronized(finished) {
                                checkDeadline()
                                submitted.set(true)
                            }
                            val cursor =
                                context.contentResolver.query(
                                    uri,
                                    request.projection.keys.toTypedArray(),
                                    request.selection,
                                    request.selectionArguments.takeIf { it.isNotEmpty() }?.toTypedArray(),
                                    null,
                                    signal,
                                ) ?: throw contentFailure(
                                    InvocationStatus.FAILED,
                                    "The content provider returned no cursor. No rows were returned.",
                                )
                            cursor.use {
                                checkDeadline()
                                val columns =
                                    request.projection.map { (name, type) ->
                                        Triple(name, cursor.getColumnIndexOrThrow(name), type)
                                    }
                                val rows = ContentRowBudget(request.maxRows, request.maxBytes)
                                var count = 0
                                var truncated = false
                                while (true) {
                                    checkDeadline()
                                    if (!cursor.moveToNext()) break
                                    checkDeadline()
                                    if (count++ >= request.maxRows) {
                                        truncated = true
                                        break
                                    }
                                    val row =
                                        JsonObject(
                                            columns.associate { (name, index, type) ->
                                                checkDeadline()
                                                name to cursor.scalar(index, type)
                                            },
                                        )
                                    if (!rows.add(row)) break
                                }
                                checkDeadline()
                                rows.result(truncated)
                            }
                        }.recoverCatching { failure ->
                            throw when (failure) {
                                is ContentQueryFailure -> {
                                    failure
                                }

                                is SecurityException -> {
                                    contentFailure(
                                        InvocationStatus.NOT_EXECUTED,
                                        "unauthorized_caller: The content provider denied access. Check its caller policy and Android permission in extension settings.",
                                    )
                                }

                                else -> {
                                    contentFailure(
                                        InvocationStatus.FAILED,
                                        "The content query failed or a declared column was missing or invalid. No rows were returned.",
                                    )
                                }
                            }
                        }
                    timer.cancel(false)
                    if (finished.compareAndSet(false, true)) continuation.resumeWith(result)
                }
            } catch (_: RejectedExecutionException) {
                timer.cancel(false)
                if (finished.compareAndSet(false, true)) {
                    continuation.resumeWithException(
                        contentFailure(
                            InvocationStatus.NOT_EXECUTED,
                            "busy: All content query workers are occupied. Nothing was submitted.",
                        ),
                    )
                }
            }
        }
    }

    private fun Cursor.scalar(
        index: Int,
        type: String,
    ): JsonElement {
        if (isNull(index)) return JsonNull
        return when (type) {
            "string" -> {
                require(getType(index) == Cursor.FIELD_TYPE_STRING)
                JsonPrimitive(getString(index))
            }

            "integer" -> {
                require(getType(index) == Cursor.FIELD_TYPE_INTEGER)
                JsonPrimitive(getLong(index).also { require(it in -9_007_199_254_740_991L..9_007_199_254_740_991L) })
            }

            "number" -> {
                require(getType(index) in setOf(Cursor.FIELD_TYPE_INTEGER, Cursor.FIELD_TYPE_FLOAT))
                JsonPrimitive(getDouble(index).also { require(it.isFinite()) })
            }

            "boolean" -> {
                require(getType(index) == Cursor.FIELD_TYPE_INTEGER)
                JsonPrimitive(getLong(index).also { require(it == 0L || it == 1L) } == 1L)
            }

            "json" -> {
                require(getType(index) == Cursor.FIELD_TYPE_STRING)
                BoundedJson.parse(getString(index), ContentRowBudget.MAX_CELL_BYTES)
            }

            else -> {
                error("Unsupported content column type")
            }
        }
    }

    private fun deadlineFailure(submitted: Boolean) =
        contentFailure(
            if (submitted) InvocationStatus.UNKNOWN else InvocationStatus.NOT_EXECUTED,
            "deadline_exceeded: The content query deadline expired. No rows were returned.",
        )

    private fun contentFailure(
        status: InvocationStatus,
        message: String,
    ) = ContentQueryFailure(ExecutionOutcome(status, message))

    override suspend fun request(
        request: HttpRequest,
        timeoutMillis: Long,
    ): HttpResponse = http.execute(request, timeoutMillis)

    companion object {
        private fun workers(name: String) =
            ThreadPoolExecutor(
                0,
                4,
                60,
                TimeUnit.SECONDS,
                SynchronousQueue(),
                { task -> Thread(task, name).apply { isDaemon = true } },
            )

        private val queries = workers("eva-content-query")
        private val cancellations = workers("eva-content-cancel")
        private val deadlines =
            ScheduledThreadPoolExecutor(1) { task ->
                Thread(task, "eva-content-deadline").apply { isDaemon = true }
            }.apply { removeOnCancelPolicy = true }

        fun buildIntent(request: IntentRequest): Intent =
            Intent(request.action).apply {
                setDataAndType(request.uri.takeIf { it.isNotEmpty() }?.let(Uri::parse), request.mimeType)
                request.targetPackage?.let { setPackage(it) }
                request.targetClass?.let { component = ComponentName(requireNotNull(request.targetPackage), it) }
                for ((name, value) in request.extras) {
                    when {
                        value.isString -> {
                            putExtra(name, value.content)
                        }

                        value.booleanOrNull != null -> {
                            putExtra(name, value.booleanOrNull!!)
                        }

                        value.longOrNull != null -> {
                            val number = value.longOrNull!!
                            if (number in Int.MIN_VALUE..Int.MAX_VALUE) putExtra(name, number.toInt()) else putExtra(name, number)
                        }

                        else -> {
                            putExtra(name, value.double)
                        }
                    }
                }
            }
    }
}
