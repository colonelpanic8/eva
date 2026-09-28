package com.colonelpanic.eva.devicecontrol.host

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.io.path.readText

internal fun YamlNode.json(): JsonElement =
    when (this) {
        is YamlMap -> JsonObject(entries.map { (k, v) -> k.content to v.json() }.toMap())
        is YamlList -> JsonArray(items.map { it.json() })
        is YamlNull -> JsonNull
        is YamlScalar -> JsonPrimitive(content)
        else -> error("Unsupported YAML node")
    }

fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

fun JsonObject.optional(key: String): String? = get(key)?.takeUnless { it == JsonNull }?.jsonPrimitive?.content

fun JsonObject.objects(key: String): List<JsonObject> = get(key)?.jsonArray?.map { it.jsonObject }.orEmpty()

private fun JsonObject.fields(
    required: String,
    optional: String = "",
) {
    val needs = required.split(' ').filter { it.isNotEmpty() }.toSet()
    val allowed = needs + optional.split(' ')
    require(keys.containsAll(needs)) { "Missing fields: ${needs - keys}" }
    require((keys - allowed).isEmpty()) { "Unknown fields: ${keys - allowed}" }
}

fun validateStep(step: JsonObject) {
    when (step.text("helper")) {
        "launch_app" -> {
            step.fields("helper package", "activity action")
            require(step.optional("activity") == null || step.optional("action") == null)
        }

        "force_stop" -> {
            step.fields("helper package")
            require(step.text("package") != "com.mobilerun.portal") { "Cannot stop Portal" }
        }

        "open_url" -> {
            step.fields("helper", "url fixture package")
            require((step.optional("url") == null) != (step.optional("fixture") == null))
            step.optional("fixture")?.let {
                require(Regex("[a-z0-9_][a-z0-9_./-]*").matches(it) && ".." !in it.split('/'))
            }
        }

        "snapshot_setting", "restore_setting" -> {
            step.fields("helper namespace key")
        }

        "set_setting" -> {
            step.fields("helper namespace key value")
        }

        "snapshot_volume", "restore_volume" -> {
            step.fields("helper stream")
        }

        "set_volume" -> {
            step.fields("helper stream level")
            require(step.text("level").toInt() in 0..100)
        }

        "media_key" -> {
            step.fields("helper key", "package")
            require(step.text("key") in setOf("play", "pause", "play_pause", "next", "previous"))
        }

        "go_home" -> {
            step.fields("helper")
        }

        "wait" -> {
            step.fields("helper seconds")
            require(step.text("seconds").toDouble() > 0 && step.text("seconds").toDouble() <= 60)
        }

        else -> {
            error("Unsupported reset helper: ${step.text("helper")}")
        }
    }
    step.optional("namespace")?.let { require(it in setOf("system", "secure", "global")) }
    step.optional("stream")?.let { require(it == "music") }
    step.optional("package")?.let { require(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+").matches(it)) }
}

fun validateChecker(spec: JsonObject) {
    when (spec.text("name")) {
        "foreground_package" -> {
            spec.fields("name package")
        }

        "foreground_activity" -> {
            spec.fields("name package activity")
        }

        "element_text_present", "element_text_absent" -> {
            spec.fields("name text", "exact")
            require(spec.text("text").isNotEmpty())
            spec.optional("exact")?.toBooleanStrict()
        }

        "settings_value" -> {
            spec.fields("name namespace key equals")
            require(spec.text("namespace") in setOf("system", "secure", "global"))
        }

        "media_playback" -> {
            spec.fields("name package state", "title_contains")
            require(spec.text("state") in setOf("playing", "paused", "stopped"))
        }

        "stream_volume" -> {
            spec.fields("name stream", "at_least at_most")
            require(spec.text("stream") == "music")
            require(spec.optional("at_least") != null || spec.optional("at_most") != null)
            listOfNotNull(spec.optional("at_least"), spec.optional("at_most")).forEach { require(it.toInt() >= 0) }
        }

        "answer_contains" -> {
            spec.fields("name includes", "excludes")
            require(spec.getValue("includes").jsonArray.isNotEmpty())
            spec.getValue("includes").jsonArray.forEach {
                require(it.jsonArray.isNotEmpty())
                it.jsonArray.forEach { v ->
                    v.jsonPrimitive.content
                }
            }
            spec["excludes"]?.jsonArray?.forEach { it.jsonPrimitive.content }
        }

        "all_of" -> {
            spec.fields("name checks")
            require(spec.objects("checks").isNotEmpty())
            spec.objects("checks").forEach(::validateChecker)
        }

        else -> {
            error("Unsupported checker: ${spec.text("name")}")
        }
    }
}

data class EvalCase(
    val raw: JsonObject,
) {
    val id = raw.text("id")
    val family = raw.text("family")
    val goal = raw.text("goal")
    val reset = raw.objects("reset")
    val teardown = raw.objects("teardown")
    val checker = raw.getValue("checker").jsonObject
    val timeoutMillis = (raw.text("timeout_s").toDouble() * 1000).toLong()
    val maxSteps = raw.text("max_steps").toInt()
    val emulatorExclusion = raw.optional("not_runnable_on_emulator")

    init {
        raw.fields(
            "id family variant goal initial_state reset expected checker risk requires_approval timeout_s max_steps",
            "draft driver followups variation apps teardown tags not_runnable_on_emulator",
        )
        require(family in setOf("settings", "chrome_read", "media"))
        require(id.startsWith("$family.") && Regex("[a-z0-9][a-z0-9_.-]*").matches(id))
        require(goal.isNotBlank() && reset.isNotEmpty() && timeoutMillis in 1..1_800_000 && maxSteps in 1..200)
        require(raw.text("risk") in setOf("read_only", "reversible", "consequential"))
        val approval = raw.text("requires_approval").toBooleanStrict()
        require(raw.text("risk") != "consequential" || approval)
        raw.optional("draft")?.toBooleanStrict()
        require(raw.optional("driver") in listOf(null, "worker", "scripted"))
        raw.getValue("initial_state").jsonObject.fields("procedure", "params")
        (reset + teardown).forEach(::validateStep)
        validateChecker(checker)
    }
}

fun loadCase(path: Path): EvalCase =
    EvalCase(
        Yaml.default
            .parseToYamlNode(path.readText())
            .json()
            .jsonObject,
    )

fun loadCases(
    root: Path,
    families: Set<String>,
): List<EvalCase> {
    require(families.isNotEmpty() && families.all { it in setOf("settings", "chrome_read", "media") })
    val cases =
        families.sorted().flatMap { family ->
            val directory = root.resolve(family)
            require(directory.toFile().isDirectory) { "Missing case directory: $directory" }
            directory
                .toFile()
                .walkTopDown()
                .filter { it.isFile && it.extension == "yaml" }
                .sortedBy { it.path }
                .map {
                    loadCase(it.toPath()).also { case -> require(case.family == family) }
                }.toList()
        }
    require(cases.isNotEmpty()) { "No cases found" }
    require(cases.map { it.id }.distinct().size == cases.size) { "Duplicate case id" }
    return cases
}
