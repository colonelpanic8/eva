package com.colonelpanic.eva.devicecontrol.worker

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

fun interface WorkerModel {
    /** Cancellation must abort the underlying transport, including a streaming response. */
    suspend fun complete(request: WorkerRequest): WorkerReply

    fun close() {}
}

@Serializable
data class WorkerTool(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

@Serializable
data class WorkerCall(
    val id: String,
    val name: String,
    val arguments: JsonObject,
)

@Serializable
data class WorkerMessage(
    val role: String,
    val text: String,
    val png: String? = null,
    val call: WorkerCall? = null,
    val resultFor: String? = null,
    val providerItem: JsonObject? = null,
)

@Serializable
data class WorkerRequest(
    val instructions: String,
    val messages: List<WorkerMessage>,
    val tools: List<WorkerTool>,
    val cacheKey: String,
)

@Serializable
data class WorkerReply(
    val calls: List<WorkerCall>,
    val text: String = "",
    val output: List<WorkerMessage> = emptyList(),
    val usage: JsonObject? = null,
)

data class WorkerWording(
    val instructions: String,
    val notices: Map<String, String>,
    val tools: List<WorkerTool>,
) {
    fun note(
        key: String,
        vararg values: Pair<String, Any>,
    ): String = values.fold(notices.getValue(key)) { text, (name, value) -> text.replace("{$name}", value.toString()) }
}

object WorkerSchemas {
    val schemas: Map<String, JsonObject> by lazy {
        Json
            .parseToJsonElement(checkNotNull(javaClass.getResourceAsStream("/worker-tools.json")).bufferedReader().use { it.readText() })
            .jsonObject
            .mapValues { it.value.jsonObject }
    }
}

data class WorkerSettings(
    val maxSteps: Int = 30,
    val maxMillis: Long = 300_000,
    val modelTimeoutMillis: Long = 120_000,
    val maxScreens: Int = 6,
    val historyLines: Int = 30,
    val maxRefusals: Int = 4,
    val maxScreenshots: Int = 3,
    val launchAliases: Map<String, List<String>> = DEFAULT_LAUNCH_ALIASES,
) {
    init {
        require(maxSteps in 1..200 && maxMillis in 1..600_000 && modelTimeoutMillis in 1..120_000)
        val packageName = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        require(
            launchAliases.size <= 50 &&
                launchAliases.all { (name, aliases) ->
                    packageName.matches(name) && aliases.size <= 10 && aliases.all { packageName.matches(it) }
                },
        )
        require(maxScreens in 1..12 && historyLines in 1..100 && maxRefusals in 1..10 && maxScreenshots in 0..10)
    }
}

val DEFAULT_LAUNCH_ALIASES = mapOf("com.android.settings" to listOf("com.google.android.settings.intelligence"))
