package com.colonelpanic.eva.adapters.android

import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class DeviceStateCategory(
    val argument: String,
    val functionId: String,
) {
    BATTERY("battery", "getBatteryDeviceState"),
    STORAGE("storage", "getStorageDeviceState"),
    NOTIFICATIONS("notifications", "getNotificationsDeviceState"),
    APPS("apps", "getAppsDeviceState"),
    MOBILE_DATA("mobile_data", "getMobileDataUsageDeviceState"),
    UNCATEGORIZED("uncategorized", "getUncategorizedDeviceState"),
    ;

    companion object {
        fun fromArgument(value: String) = entries.first { it.argument == value }
    }
}

object AppFunctionCommands {
    fun getState(category: DeviceStateCategory): List<String> = execute(category.functionId, "{}")

    fun metadata(): List<String> = execute("getDeviceStateMetadata", "{}")

    fun setState(
        key: String,
        value: String,
    ): List<String> {
        val parameters =
            buildJsonObject {
                put(
                    "setDeviceStateItemParams",
                    buildJsonObject {
                        put("key", key)
                        put("itemizationKeys", kotlinx.serialization.json.JsonArray(emptyList()))
                        put("value", value)
                        put("requestInitiatedWhileUnlocked", true)
                    },
                )
            }
        return execute("setDeviceStateItem", Json.encodeToString(parameters))
    }

    private fun execute(
        functionId: String,
        parameters: String,
    ) = listOf(
        "app_function",
        "execute-app-function",
        "--package",
        SETTINGS_PACKAGE,
        "--function",
        functionId,
        "--parameters",
        parameters,
        "--timeout-duration",
        APP_FUNCTION_TIMEOUT_SECONDS.toString(),
        "--brief-yaml",
    )

    private const val SETTINGS_PACKAGE = "com.android.settings"
    private const val APP_FUNCTION_TIMEOUT_SECONDS = 8
}

object AppFunctionOutput {
    fun summarizeState(
        yaml: String,
        maxChars: Int = MAX_SUMMARY_CHARS,
        maxItems: Int = MAX_STATE_ITEMS,
    ): String {
        val items = mutableListOf<StateItem>()
        var value: String? = null
        var purpose: String? = null
        var pending: StateItem? = null

        fun flush() {
            pending?.let(items::add)
            pending = null
        }

        for (line in yaml.lineSequence()) {
            val field = line.trimStart().removePrefix("- ")
            when {
                field.startsWith("jsonValue:") -> {
                    flush()
                    value = scalar(field)
                    purpose = null
                }

                field.startsWith("purpose:") -> {
                    if (pending != null) flush()
                    purpose = scalar(field)
                }

                field.startsWith("key:") -> {
                    flush()
                    pending = StateItem(key = scalar(field).orEmpty(), purpose = purpose, value = value)
                    value = null
                    purpose = null
                }

                field.startsWith("english:") && pending != null -> {
                    pending = pending?.copy(name = scalar(field))
                }

                field.startsWith("intentUri:") -> {
                    flush()
                }
            }
            if (items.size >= maxItems) break
        }
        flush()

        if (items.isEmpty()) return "No device-state items were returned."
        val lines =
            items.take(maxItems).map { item ->
                "- ${item.key}: ${item.value ?: "unknown"} (${item.name ?: item.purpose ?: "unnamed"})"
            }
        return cap(lines, maxChars, items.size >= maxItems)
    }

    fun summarizeMetadata(
        yaml: String,
        search: String,
        maxChars: Int = MAX_SUMMARY_CHARS,
        maxItems: Int = MAX_METADATA_ITEMS,
    ): String {
        val matches = mutableListOf<MetadataItem>()
        var writable = false
        var possibleValues: String? = null
        var purpose: String? = null
        val needle = search.trim().lowercase()

        for (line in yaml.lineSequence()) {
            val field = line.trimStart().removePrefix("- ")
            when {
                field.startsWith("writable:") -> {
                    writable = scalar(field) == "true"
                    possibleValues = null
                    purpose = null
                }

                field.startsWith("possibleValues:") -> {
                    possibleValues = scalar(field)
                }

                field.startsWith("purpose:") -> {
                    purpose = scalar(field)
                }

                field.startsWith("key:") -> {
                    val key = scalar(field).orEmpty()
                    val item = MetadataItem(key, purpose.orEmpty(), possibleValues.orEmpty())
                    if (writable && listOf(item.key, item.purpose, item.possibleValues).any { needle in it.lowercase() }) {
                        matches += item
                        if (matches.size >= maxItems) break
                    }
                    writable = false
                    possibleValues = null
                    purpose = null
                }
            }
        }

        if (matches.isEmpty()) return "No writable settings matched '$search'."
        val lines = matches.map { "- ${it.key}: ${it.purpose}; values=${it.possibleValues}" }
        return cap(lines, maxChars, matches.size >= maxItems)
    }

    fun summarizeSet(yaml: String): ExecutionOutcome {
        val successful = field(yaml, "isSuccessful") == "true"
        val currentValue = field(yaml, "currentValue")
        return if (successful) {
            ExecutionOutcome(InvocationStatus.COMPLETED, "Setting changed. Current value: ${currentValue ?: "unknown"}.")
        } else {
            val error = field(yaml, "errorMessage") ?: "Settings rejected the requested value."
            ExecutionOutcome(InvocationStatus.FAILED, "Setting was not changed: $error")
        }
    }

    private fun field(
        yaml: String,
        name: String,
    ) = yaml
        .lineSequence()
        .map(String::trim)
        .firstOrNull { it.startsWith("$name:") }
        ?.let(::scalar)

    private fun scalar(field: String): String? {
        val value = field.substringAfter(':').trim()
        if (value.isEmpty()) return null
        return if (value.startsWith('"') && value.endsWith('"')) {
            runCatching { Json.decodeFromString<String>(value) }.getOrDefault(value.removeSurrounding("\""))
        } else {
            value
        }
    }

    private fun cap(
        lines: List<String>,
        maxChars: Int,
        itemLimitReached: Boolean,
    ): String {
        val result = StringBuilder()
        var capped = itemLimitReached
        val suffix = "\n… output capped; narrow the request for more."
        for (line in lines) {
            val separatorLength = if (result.isEmpty()) 0 else 1
            if (result.length + separatorLength + line.length > maxChars - suffix.length) {
                val remaining = maxChars - suffix.length - result.length - separatorLength
                if (remaining > 0) {
                    if (separatorLength > 0) result.append('\n')
                    result.append(line.take(remaining))
                }
                capped = true
                break
            }
            if (result.isNotEmpty()) result.append('\n')
            result.append(line)
        }
        if (capped) {
            if (result.length + suffix.length <= maxChars) result.append(suffix)
        }
        return result.toString()
    }

    private data class StateItem(
        val key: String,
        val purpose: String?,
        val value: String?,
        val name: String? = null,
    )

    private data class MetadataItem(
        val key: String,
        val purpose: String,
        val possibleValues: String,
    )

    private const val MAX_SUMMARY_CHARS = 4_000
    private const val MAX_STATE_ITEMS = 40
    private const val MAX_METADATA_ITEMS = 30
}

object AppFunctionShellMessages {
    fun failure(
        result: ShellResult,
        write: Boolean,
    ): ExecutionOutcome? {
        if (result.timedOut) {
            val message =
                if (write) {
                    "The AppFunctions write timed out; the setting may have changed. Check it before retrying."
                } else {
                    "The AppFunctions read timed out before returning device state."
                }
            return ExecutionOutcome(InvocationStatus.FAILED, message)
        }
        if (result.uid != SHELL_UID) {
            return ExecutionOutcome(InvocationStatus.FAILED, "AppFunctions did not run as Android's shell user.")
        }
        if (result.exitCode == 0) return null
        val detail =
            (result.stderr.ifBlank { result.stdout })
                .lineSequence()
                .firstOrNull(String::isNotBlank)
                ?.trim()
                ?.take(500)
                ?: "cmd app_function exited with code ${result.exitCode}."
        return ExecutionOutcome(InvocationStatus.FAILED, "AppFunctions failed: $detail")
    }

    private const val SHELL_UID = 2000
}

class AppFunctionsBackend(
    private val host: ShizukuShellHost,
    private val operation: Operation,
) : ExecutionBackend {
    enum class Operation {
        GET,
        SET,
        METADATA,
    }

    override suspend fun unavailableReason(): String? = host.unavailableReason()

    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome =
        try {
            val command =
                when (operation) {
                    Operation.GET -> AppFunctionCommands.getState(DeviceStateCategory.fromArgument(arguments.getValue("category")))
                    Operation.SET -> AppFunctionCommands.setState(arguments.getValue("key"), arguments.getValue("value"))
                    Operation.METADATA -> AppFunctionCommands.metadata()
                }
            val result = host.run(command, PROCESS_TIMEOUT_MILLIS)
            AppFunctionShellMessages.failure(result, write = operation == Operation.SET)
                ?: when (operation) {
                    Operation.GET -> {
                        val category = arguments.getValue("category")
                        ExecutionOutcome(
                            InvocationStatus.COMPLETED,
                            "$category device state:\n${AppFunctionOutput.summarizeState(result.stdout)}",
                        )
                    }

                    Operation.SET -> {
                        AppFunctionOutput.summarizeSet(result.stdout)
                    }

                    Operation.METADATA -> {
                        val search = arguments.getValue("search")
                        ExecutionOutcome(
                            InvocationStatus.COMPLETED,
                            "Writable settings matching '$search':\n${AppFunctionOutput.summarizeMetadata(result.stdout, search)}",
                        )
                    }
                }
        } catch (error: ShizukuUnavailableException) {
            ExecutionOutcome(InvocationStatus.NOT_EXECUTED, error.message ?: ShizukuShellHost.SERVER_STOPPED)
        }

    private companion object {
        const val PROCESS_TIMEOUT_MILLIS = 10_000L
    }
}
