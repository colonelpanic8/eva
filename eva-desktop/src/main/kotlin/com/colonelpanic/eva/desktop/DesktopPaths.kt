package com.colonelpanic.eva.desktop

import java.io.File

/** Where EVA keeps its files on this computer, following the XDG base directories. */
class DesktopPaths(
    val config: File,
    val data: File,
) {
    val journal get() = File(data, "eva-actions.db")
    val memory get() = File(data, "memory")
    val chatGptTokens get() = File(config, "chatgpt.json")

    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): DesktopPaths {
            val home = File(env["HOME"] ?: System.getProperty("user.home"))
            val config = env["XDG_CONFIG_HOME"]?.takeIf { it.isNotBlank() }?.let(::File) ?: File(home, ".config")
            val data = env["XDG_DATA_HOME"]?.takeIf { it.isNotBlank() }?.let(::File) ?: File(home, ".local/share")
            return DesktopPaths(File(config, "eva"), File(data, "eva"))
        }
    }
}
