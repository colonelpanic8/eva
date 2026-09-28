package com.colonelpanic.eva.devicecontrol.host

import com.colonelpanic.eva.devicecontrol.DeviceBackend
import com.colonelpanic.eva.devicecontrol.proto.Observation
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

fun resumedActivity(dump: String): Pair<String, String>? {
    val match = Regex("(?:topResumedActivity=|ResumedActivity: )ActivityRecord\\{\\S+ \\S+ ([\\w.]+)/([\\w.$]+)").find(dump) ?: return null
    val (pkg, activity) = match.destructured
    return pkg to if (activity.startsWith('.')) pkg + activity else activity
}

data class MediaSession(
    val packageName: String,
    var active: Boolean = false,
    var state: String? = null,
    var title: String? = null,
)

fun mediaSessions(dump: String): List<MediaSession> {
    val sessions = mutableListOf<MediaSession>()
    var current: MediaSession? = null
    dump.lineSequence().forEach { raw ->
        val line = raw.trim()
        when {
            line.startsWith("package=") -> {
                current = MediaSession(line.removePrefix("package="))
                sessions += current
            }

            current == null -> {
                return@forEach
            }

            line.startsWith("active=") -> {
                current.active = line == "active=true"
            }

            line.startsWith("state=PlaybackState") -> {
                val state =
                    Regex("state=PlaybackState \\{state=\\w*\\((\\d+)\\)")
                        .find(line)
                        ?.groupValues
                        ?.get(1)
                        ?.toInt()
                current.state =
                    when (state) {
                        1, 7 -> "stopped"
                        2 -> "paused"
                        3, 4, 5, 6 -> "playing"
                        else -> null
                    }
            }

            line.startsWith("metadata:") -> {
                current.title =
                    Regex("metadata: size=\\d+, description=(.*)")
                        .find(line)
                        ?.groupValues
                        ?.get(1)
                        ?.takeUnless { it == "null" }
            }

            !raw.startsWith(' ') -> {
                current = null
            }
        }
    }
    return sessions
}

fun List<MediaSession>.sessionFor(pkg: String): MediaSession? =
    filter { it.packageName == pkg }.let { mine ->
        mine.firstOrNull { it.active }
            ?: mine.firstOrNull()
    }

fun normalize(text: String): String = text.replace(Regex("\\s+"), " ").trim().lowercase(Locale.ROOT)

class DeviceProbes(
    val adb: Adb,
    private val backend: DeviceBackend,
    private val agreementMillis: Long = 5000,
) {
    suspend fun setting(
        namespace: String,
        key: String,
    ): String? = adb.shell("settings", "get", namespace, key).takeUnless { it == "null" }

    suspend fun media(pkg: String): MediaSession? = mediaSessions(adb.shell("dumpsys", "media_session")).sessionFor(pkg)

    suspend fun volume(): Int? =
        Regex(
            "volume is (\\d+) in range",
        ).find(adb.shell("cmd", "media_session", "volume", "--stream", "3", "--get"))?.groupValues?.get(1)?.toInt()

    suspend fun snapshot(): Observation {
        val end = System.nanoTime() + agreementMillis * 1_000_000
        do {
            val observation = backend.observe()
            val resumed = resumedActivity(adb.shell("dumpsys", "activity", "activities"))
            if (resumed != null && observation.packageName == resumed.first) return observation
            check(System.nanoTime() < end) { "Portal foreground disagrees with resumed activity" }
            delay(500)
        } while (true)
    }
}

@Serializable
data class Verdict(
    val checker: String,
    val passed: Boolean,
    val reason: String,
    val children: List<Verdict> = emptyList(),
)

class Checkers(
    private val probes: DeviceProbes,
) {
    suspend fun check(
        spec: JsonObject,
        answer: String?,
    ): Verdict {
        val name = spec.text("name")
        var reason: String
        val passed =
            when (name) {
                "all_of" -> {
                    val children = spec.objects("checks").map { check(it, answer) }
                    return Verdict(name, children.all { it.passed }, children.joinToString("; ") { it.reason }, children)
                }

                "foreground_package", "foreground_activity" -> {
                    val obs = probes.snapshot()
                    val activity = obs.activity?.let { if (it.startsWith('.')) obs.packageName + it else it }.orEmpty()
                    reason = "expected ${spec.text("package")}/${spec.optional("activity").orEmpty()}, saw ${obs.packageName}/$activity"
                    obs.packageName == spec.text("package") && (name == "foreground_package" || activity.endsWith(spec.text("activity")))
                }

                "element_text_present", "element_text_absent" -> {
                    val obs = probes.snapshot()
                    val needle = spec.text("text")
                    val exact = spec.optional("exact")?.toBooleanStrict() ?: false
                    val present =
                        obs.elements.flatMap { listOfNotNull(it.text, it.contentDescription) }.any {
                            if (exact) it == needle else normalize(needle) in normalize(it)
                        }
                    reason = "$needle ${if (present) "present" else "absent"}"
                    present == (name == "element_text_present")
                }

                "settings_value" -> {
                    val value = probes.setting(spec.text("namespace"), spec.text("key"))
                    reason = "${spec.text("namespace")}/${spec.text("key")} expected ${spec.text("equals")}, got $value"
                    value == spec.text("equals")
                }

                "media_playback" -> {
                    val session = probes.media(spec.text("package"))
                    reason = "expected ${spec.text("state")}, got ${session?.state}; title ${session?.title}"
                    session?.state == spec.text("state") &&
                        (spec.optional("title_contains")?.let { normalize(it) in normalize(session.title.orEmpty()) } ?: true)
                }

                "stream_volume" -> {
                    val volume = probes.volume()
                    reason = "music volume $volume"
                    volume != null && volume >= (spec.optional("at_least")?.toInt() ?: 0) &&
                        volume <= (spec.optional("at_most")?.toInt() ?: Int.MAX_VALUE)
                }

                "answer_contains" -> {
                    val normalized = normalize(answer.orEmpty())
                    val missing =
                        spec.getValue("includes").jsonArray.count { group ->
                            group.jsonArray.none {
                                normalize(it.jsonPrimitive.content) in
                                    normalized
                            }
                        }
                    val excluded = spec["excludes"]?.jsonArray.orEmpty().count { normalize(it.jsonPrimitive.content) in normalized }
                    reason =
                        if (answer == null) "no answer given" else "$missing expected group(s) missing, $excluded excluded term(s) found"
                    answer != null && missing == 0 && excluded == 0
                }

                else -> {
                    error("Unsupported checker $name")
                }
            }
        return Verdict(name, passed, reason)
    }
}
