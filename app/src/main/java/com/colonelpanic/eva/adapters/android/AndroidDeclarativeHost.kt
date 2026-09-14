package com.colonelpanic.eva.adapters.android

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import com.colonelpanic.eva.adapters.declarative.ContentRequest
import com.colonelpanic.eva.adapters.declarative.ContentRows
import com.colonelpanic.eva.adapters.declarative.DeclarativeBinding
import com.colonelpanic.eva.adapters.declarative.DeclarativeHost
import com.colonelpanic.eva.adapters.declarative.HttpRequest
import com.colonelpanic.eva.adapters.declarative.HttpResponse
import com.colonelpanic.eva.adapters.declarative.IntentRequest
import com.colonelpanic.eva.adapters.declarative.PackageHttpClient
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.longOrNull

class AndroidDeclarativeHost(
    private val intents: AndroidIntentHost,
    private val http: PackageHttpClient,
) : DeclarativeHost {
    override suspend fun unavailableReason(binding: DeclarativeBinding): String? =
        when (binding) {
            is DeclarativeBinding.Intent -> intents.unavailableReason()
            is DeclarativeBinding.Content -> "Content-provider package execution is not available in this build."
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
    ): ContentRows = error("Content-provider package execution is not available in this build.")

    override suspend fun request(
        request: HttpRequest,
        timeoutMillis: Long,
    ): HttpResponse = http.execute(request, timeoutMillis)

    companion object {
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
