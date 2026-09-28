package com.colonelpanic.eva.devicecontrol.host

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class SnapshotStore(
    private val path: Path? = null,
) {
    private val values =
        path?.takeIf { it.exists() }?.let { Json.parseToJsonElement(it.readText()).jsonObject.toMutableMap() } ?: mutableMapOf()

    fun remember(
        key: String,
        value: String?,
    ) {
        if (key !in values) {
            values[key] = value?.let(::JsonPrimitive) ?: JsonNull
            save()
        }
    }

    fun saved(key: String): String? {
        check(key in values) { "No snapshot for $key" }
        return values
            .getValue(key)
            .takeUnless {
                it ==
                    JsonNull
            }?.jsonPrimitive
            ?.content
    }

    fun forget(key: String) {
        values.remove(key)
        save()
    }

    fun pending(): List<String> = values.keys.toList()

    private fun save() {
        path?.let {
            Files.createDirectories(it.toAbsolutePath().parent)
            val temp = Files.createTempFile(it.toAbsolutePath().parent, "snapshot-", ".tmp")
            try {
                temp.writeText(JsonObject(values).toString() + "\n")
                Files.move(temp, it, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temp)
            }
        }
    }
}

class DeviceReset(
    private val adb: Adb,
    private val probes: DeviceProbes,
    private val fixtureBaseUrl: String?,
    val snapshots: SnapshotStore,
    private val settleMillis: Long = 10_000,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private suspend fun awaitState(
        description: String,
        predicate: suspend () -> Boolean,
    ) {
        val deadline = System.nanoTime() + settleMillis * 1_000_000
        while (!predicate()) {
            check(System.nanoTime() < deadline) { "Reset did not reach $description" }
            sleep(500)
        }
    }

    private suspend fun foreground(pkg: String) =
        awaitState("foreground $pkg") {
            resumedActivity(adb.shell("dumpsys", "activity", "activities"))?.first ==
                pkg
        }

    private suspend fun resolve(vararg intent: String): String =
        adb.shell("cmd", "package", "resolve-activity", "--brief", *intent).lineSequence().last().also {
            check('/' in it) { "No activity resolves" }
        }

    private suspend fun putSetting(
        namespace: String,
        key: String,
        value: String?,
    ) {
        if (namespace == "secure" && key == "ui_night_mode") {
            adb.shell("cmd", "uimode", "night", mapOf("0" to "auto", "1" to "no", "2" to "yes")[value] ?: "no")
            if (value == null) adb.shell("settings", "delete", namespace, key)
        } else if (value == null) {
            adb.shell("settings", "delete", namespace, key)
        } else {
            adb.shell("settings", "put", namespace, key, value)
        }
    }

    private suspend fun setVolume(value: String) {
        adb.shell("cmd", "media_session", "volume", "--stream", "3", "--set", value)
    }

    suspend fun step(spec: JsonObject) {
        validateStep(spec)
        val helper = spec.text("helper")
        when (helper) {
            "launch_app" -> {
                val pkg = spec.text("package")
                if (spec.optional("action") != null) {
                    adb.shell("am", "start", "-W", "-a", spec.text("action"), "-p", pkg)
                } else {
                    val component = spec.optional("activity")?.let { "$pkg/$it" } ?: resolve("-c", "android.intent.category.LAUNCHER", pkg)
                    adb.shell("am", "start", "-W", "-n", component)
                }
                foreground(pkg)
            }

            "force_stop" -> {
                adb.shell("am", "force-stop", spec.text("package"))
            }

            "open_url" -> {
                val url =
                    spec.optional("url")
                        ?: "${requireNotNull(fixtureBaseUrl) { "Fixture base URL required" }.trimEnd('/')}/${spec.text("fixture")}"
                val pkg = spec.optional("package") ?: "com.android.chrome"
                adb.shell("am", "start", "-W", "-a", "android.intent.action.VIEW", "-d", url, "-p", pkg)
                foreground(pkg)
            }

            "snapshot_setting", "set_setting", "restore_setting" -> {
                val namespace = spec.text("namespace")
                val key = spec.text("key")
                val id = "settings/$namespace/$key"
                when (helper) {
                    "snapshot_setting" -> {
                        snapshots.remember(id, probes.setting(namespace, key))
                    }

                    "set_setting" -> {
                        putSetting(namespace, key, spec.text("value"))
                    }

                    else -> {
                        putSetting(namespace, key, snapshots.saved(id))
                        snapshots.forget(id)
                    }
                }
            }

            "snapshot_volume" -> {
                snapshots.remember("volume/music", requireNotNull(probes.volume()) { "Cannot read volume" }.toString())
            }

            "set_volume" -> {
                setVolume(spec.text("level"))
                check(probes.volume() == spec.text("level").toInt()) { "Volume did not change" }
            }

            "restore_volume" -> {
                snapshots.saved("volume/music")?.let { setVolume(it) }
                snapshots.forget("volume/music")
            }

            "media_key" -> {
                val pkg = spec.optional("package")
                if (pkg != null) awaitState("media session $pkg") { probes.media(pkg) != null }
                val key = spec.text("key")
                adb.shell("input", "keyevent", "KEYCODE_MEDIA_${key.uppercase()}")
                if (pkg != null && key in setOf("play", "pause")) {
                    val want = if (key == "play") "playing" else "paused"
                    awaitState("$pkg $want") { probes.media(pkg)?.state == want }
                }
            }

            "go_home" -> {
                val home = resolve("-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME").substringBefore('/')
                adb.shell("input", "keyevent", "KEYCODE_HOME")
                foreground(home)
            }

            "wait" -> {
                sleep((spec.text("seconds").toDouble() * 1000).toLong())
            }
        }
    }

    suspend fun restorePending() {
        snapshots.pending().forEach { key ->
            val parts = key.split('/')
            val spec =
                if (parts[0] ==
                    "settings"
                ) {
                    buildJsonObject {
                        put("helper", "restore_setting")
                        put("namespace", parts[1])
                        put("key", parts[2])
                    }
                } else {
                    buildJsonObject {
                        put("helper", "restore_volume")
                        put("stream", "music")
                    }
                }
            step(spec)
        }
    }
}
