package com.colonelpanic.eva.desktop

import com.colonelpanic.eva.capability.CapabilityDefinition
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.net.URI

/** Tools only the desktop host offers; their wording comes from the followed catalog like any native tool. */
object DesktopCapabilities {
    const val OPEN_URL = "eva.desktop.open_url"

    val definitions: List<CapabilityDefinition> =
        listOf(
            tool(
                OPEN_URL,
                "Open a web address",
                Json
                    .parseToJsonElement(
                        """{"type":"object","properties":{"url":{"type":"string","minLength":8,"maxLength":2048}},
                        "required":["url"],"additionalProperties":false}""",
                    ).jsonObject,
            ) { args -> if (webAddress(args.getValue("url")) == null) "Give an absolute http:// or https:// address." else null },
        )

    fun backends(opener: UrlOpener = UrlOpener.system()): Map<String, ExecutionBackend> = mapOf(OPEN_URL to OpenUrlBackend(opener))

    internal fun webAddress(text: String): URI? =
        runCatching { URI(text) }.getOrNull()?.takeIf {
            (it.scheme == "http" || it.scheme == "https") && !it.host.isNullOrEmpty() && it.rawUserInfo == null &&
                text.none(Char::isWhitespace)
        }
}

/** Hands an address to whatever the desktop uses to open it. */
fun interface UrlOpener {
    /** Null when the opener accepted the address, otherwise why it did not. */
    fun open(url: URI): String?

    companion object {
        fun system(os: String = System.getProperty("os.name").orEmpty()): UrlOpener =
            UrlOpener { url ->
                val command = if (os.startsWith("Mac", ignoreCase = true)) "open" else "xdg-open"
                val process = ProcessBuilder(command, url.toString()).redirectErrorStream(true).start()
                val exit = process.waitFor()
                if (exit == 0) null else "$command exited with status $exit"
            }
    }
}

private class OpenUrlBackend(
    private val opener: UrlOpener,
) : ExecutionBackend {
    override suspend fun unavailableReason(): String? = null

    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
        val url =
            DesktopCapabilities.webAddress(arguments["url"].orEmpty())
                ?: return ExecutionOutcome(InvocationStatus.NOT_EXECUTED, "That is not an http or https address. Nothing was opened.")
        val problem =
            try {
                withContext(Dispatchers.IO) { opener.open(url) }
            } catch (error: java.io.IOException) {
                return ExecutionOutcome(InvocationStatus.NOT_EXECUTED, "No browser opener is available: ${error.message}")
            }
        return if (problem == null) {
            ExecutionOutcome(InvocationStatus.HANDED_OFF, "Handed $url to the browser.")
        } else {
            ExecutionOutcome(InvocationStatus.FAILED, "The browser did not take the address: $problem")
        }
    }
}
