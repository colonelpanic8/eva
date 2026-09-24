package com.colonelpanic.eva.adapters.android

import com.colonelpanic.eva.capability.BoundedJson
import com.colonelpanic.eva.capability.CapabilityDefinition
import com.colonelpanic.eva.capability.CapabilitySource
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.extensions.AdapterIdentity
import com.colonelpanic.eva.capability.extensions.Capability
import com.colonelpanic.eva.capability.extensions.CapabilityAdapter
import com.colonelpanic.eva.capability.extensions.CapabilityBinding
import com.colonelpanic.eva.capability.extensions.Descriptor
import com.colonelpanic.eva.capability.extensions.Effect
import com.colonelpanic.eva.capability.extensions.InstalledExtension
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.util.Collections

/**
 * Which installed app this is, stable across the app running or not. Mirrors [ExtensionIdentity]
 * so a reinstall under another signer is a different instance and loses its grants.
 */
data class MediaIdentity(
    val user: Int,
    val packageName: String,
    val signer: String,
    val installedAt: Long,
) : AdapterIdentity {
    override val instanceId: String get() = "media:$user:$packageName"
    override val key: String get() =
        BoundedJson.digest(JsonArray(listOf(user.toString(), packageName, signer, installedAt.toString()).map(::JsonPrimitive)))
}

/**
 * One installed app that plays media, and the routes it declares. Found from what the app
 * registers rather than from a list EVA keeps, so a player EVA has never heard of still appears.
 */
data class DiscoveredMediaApp(
    val identity: MediaIdentity,
    val label: String,
    /** Its media browser service, when it declares one: the route that starts it cold. */
    val browser: MediaApp?,
    /** Media3 library search, when it exposes it: the only general route into a queue. */
    val library: MediaLibraryApp?,
    /** Whether it takes the play-from-search intent, the last resort that needs the screen. */
    val handlesSearchIntent: Boolean,
)

interface MediaAppSource {
    fun scan(): List<DiscoveredMediaApp>
}

/**
 * Media apps as extensions. Each app is one installed extension whose descriptor lists only the
 * operations it has a route for, so the model is offered `play on YouTube` and never `queue on
 * YouTube`, and an app must be enabled here before EVA drives it. Grants, the settings rows,
 * catalog revisions, and stale-proposal refusal all come from the extension runtime unchanged.
 *
 * Unnamed operations — pause whatever is playing, what is playing, volume — stay native; they
 * are media-button semantics and belong to no app.
 */
class MediaAdapter(
    private val source: MediaAppSource,
    private val sessions: MediaSessionAccess,
    private val launcher: MediaLauncher,
    private val queueFor: (DiscoveredMediaApp) -> QueueProvider?,
    private val intentFor: (DiscoveredMediaApp) -> ExecutionBackend?,
    private val settle: suspend () -> Unit = { delay(MediaPlayBackend.SETTLE_MILLIS) },
    private val remoteFor: (DiscoveredMediaApp) -> RemotePlayer? = { null },
) : CapabilityAdapter {
    /** Apps that turned EVA away as a media client; asking again every time only slows the fallback. */
    private val refusals: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    private var apps = emptyList<DiscoveredMediaApp>()
    private val entries = MutableStateFlow<List<InstalledExtension>>(emptyList())
    override val installed = entries.asStateFlow()
    override val ready = MutableStateFlow(false)

    @Synchronized
    override fun refresh() {
        apps = source.scan()
        entries.value = apps.map(::entry)
        ready.value = true
    }

    /** A package came or went, so the list is simply read again. */
    override fun invalidate(
        packageName: String,
        removed: Boolean,
    ) = refresh()

    override fun available(
        identity: AdapterIdentity,
        digest: String,
    ): Boolean = entries.value.any { it.identity == identity && it.descriptor?.digest == digest }

    @Synchronized
    override fun bindings(extension: InstalledExtension): List<CapabilityBinding> {
        val app = apps.find { it.identity == extension.identity } ?: return emptyList()
        val descriptor = extension.descriptor ?: return emptyList()
        return descriptor.capabilities.map { capability ->
            CapabilityBinding(
                capability,
                CapabilityDefinition(
                    id = "${extension.capabilityPrefix}.${capability.name}",
                    title = capability.title,
                    description = capability.description,
                    inputSchema = capability.inputSchema,
                    readOnly = capability.effect == Effect.READ,
                    source = CapabilitySource(app.identity.instanceId, app.label),
                ),
                backend(app, capability),
                "${app.identity.key}:${descriptor.digest}",
            )
        }
    }

    private fun entry(app: DiscoveredMediaApp): InstalledExtension {
        val capabilities = capabilities(app)
        val digest =
            BoundedJson.digest(
                JsonArray(
                    capabilities.map {
                        JsonObject(
                            mapOf(
                                "name" to JsonPrimitive(it.name),
                                "effect" to JsonPrimitive(it.effect.name),
                                "schema" to it.inputSchema,
                            ),
                        )
                    },
                ),
            )
        return InstalledExtension(
            packageName = app.identity.packageName,
            identity = app.identity,
            descriptor = Descriptor(digest, digest, app.label, capabilities, digest),
            capabilityPrefix = "extension.media.${app.identity.packageName}",
        )
    }

    /** Control, now-playing, and play reach any app through its session; only queueing needs a route of its own. */
    private fun capabilities(app: DiscoveredMediaApp): List<Capability> {
        val label = app.label
        val cold = app.browser != null || app.handlesSearchIntent
        return listOfNotNull(
            Capability(
                CONTROL,
                "Control $label",
                "Pause, resume, skip, or stop what $label is playing, through the media session it publishes. " +
                    "Only while $label is playing or paused on something; it starts nothing.",
                CONTROL_SCHEMA,
                Effect.WRITE,
                MAX_WAIT_MILLIS,
                MAX_RESULT_BYTES,
            ),
            Capability(
                NOW_PLAYING,
                "What $label is playing",
                "Report what $label shows playing or paused, if anything. Changes nothing.",
                EMPTY_SCHEMA,
                Effect.READ,
                MAX_WAIT_MILLIS,
                MAX_RESULT_BYTES,
            ),
            Capability(
                PLAY,
                "Play on $label",
                "Ask $label to start playing a song, artist, album, playlist, or show by name" +
                    (if (cold) ", starting it if it is not running. " else ". Only works while $label is already running. ") +
                    "$label decides what the words match; EVA reports what actually started when it can see it.",
                QUERY_SCHEMA,
                Effect.WRITE,
                MAX_WAIT_MILLIS,
                MAX_RESULT_BYTES,
            ),
            queueFor(app)?.let {
                Capability(
                    QUEUE,
                    "Queue on $label",
                    "Add a song to $label's queue so it plays after the current track, without interrupting it. " +
                        "EVA searches $label for the words and queues the top match, then reports the track. " +
                        "Refused with the reason when $label is not connected or finds nothing.",
                    QUERY_SCHEMA,
                    Effect.WRITE,
                    MAX_WAIT_MILLIS,
                    MAX_RESULT_BYTES,
                )
            },
        )
    }

    private fun backend(
        app: DiscoveredMediaApp,
        capability: Capability,
    ): ExecutionBackend =
        when (capability.name) {
            CONTROL -> MediaAppControlBackend(app.identity.packageName, app.label, sessions, settle)
            NOW_PLAYING -> MediaAppNowPlayingBackend(app.identity.packageName, app.label, sessions)
            PLAY -> MediaAppPlayBackend(app, sessions, launcher, intentFor(app), refusals, settle, remoteFor(app))
            QUEUE -> MediaAppQueueBackend(app.label, checkNotNull(queueFor(app)))
            else -> error("No media backend for ${capability.name}")
        }

    companion object {
        const val CONTROL = "control"
        const val NOW_PLAYING = "now_playing"
        const val PLAY = "play"
        const val QUEUE = "queue"

        /** Long enough for a cold app to start and report a track through the confirm reads. */
        const val MAX_WAIT_MILLIS = 10_000L
        const val MAX_RESULT_BYTES = 4_096

        private fun schema(value: String) = Json.parseToJsonElement(value).jsonObject

        private val CONTROL_SCHEMA =
            schema(
                """{"type":"object","properties":{"action":{"type":"string","enum":${MediaCommand.arguments.joinToString(
                    ",",
                    "[",
                    "]",
                ) { "\"$it\"" }},
                "description":"toggle flips between playing and paused"}},"required":["action"],"additionalProperties":false}""",
            )
        private val EMPTY_SCHEMA = schema("""{"type":"object","properties":{},"required":[],"additionalProperties":false}""")
        private val QUERY_SCHEMA =
            schema(
                """{"type":"object","properties":{"query":{"type":"string","minLength":1,"maxLength":300,
                "description":"What to play, as the user would say it, with the artist when known"}},"required":["query"],"additionalProperties":false}""",
            )
    }
}

/** Drives one app's own session. No session means the app is not running or not publishing: a refusal, not a failure. */
internal class MediaAppControlBackend(
    private val packageName: String,
    private val label: String,
    private val sessions: MediaSessionAccess,
    private val settle: suspend () -> Unit,
) : ExecutionBackend {
    override suspend fun unavailableReason(): String? = null

    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome =
        withContext(Dispatchers.IO) {
            val command =
                MediaCommand.of(arguments.getValue("action"))
                    ?: return@withContext ExecutionOutcome(InvocationStatus.NOT_EXECUTED, MediaControlBackend.UNKNOWN_ACTION)
            if (!sessions.observable()) {
                return@withContext ExecutionOutcome(
                    InvocationStatus.NOT_EXECUTED,
                    "EVA cannot reach $label's controls without notification access. Turn on media controls in EVA's settings.",
                )
            }
            val target =
                sessions.sessions().firstOrNull { it.packageName == packageName }
                    ?: return@withContext ExecutionOutcome(InvocationStatus.NOT_EXECUTED, notPlaying(label))
            MediaRouting.refusalFor(target, command)?.let { return@withContext ExecutionOutcome(InvocationStatus.NOT_EXECUTED, it.message) }
            driveSession(sessions, target, command, settle)
        }
}

internal class MediaAppNowPlayingBackend(
    private val packageName: String,
    private val label: String,
    private val sessions: MediaSessionAccess,
) : ExecutionBackend {
    override suspend fun unavailableReason(): String? = null

    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome =
        withContext(Dispatchers.IO) {
            if (!sessions.observable()) {
                return@withContext ExecutionOutcome(InvocationStatus.NOT_EXECUTED, MediaControlBackend.NEEDS_ACCESS)
            }
            val snapshot = sessions.sessions().firstOrNull { it.packageName == packageName }
            ExecutionOutcome(InvocationStatus.COMPLETED, snapshot?.let(MediaText::one) ?: notPlaying(label))
        }
}

/**
 * The same ladder as the unnamed play action, pinned to one app: its account service when one is
 * connected, then its live session, then its browser service unless it has refused EVA before,
 * then the intent if it registers for one.
 */
internal class MediaAppPlayBackend(
    private val app: DiscoveredMediaApp,
    private val sessions: MediaSessionAccess,
    private val launcher: MediaLauncher,
    private val intent: ExecutionBackend?,
    private val refusals: MutableSet<String>,
    private val settle: suspend () -> Unit,
    private val remote: RemotePlayer? = null,
) : ExecutionBackend {
    private val packageName get() = app.identity.packageName
    private val label get() = app.label

    /** The session and browser rungs need no screen; only the intent can be blocked, and it says so itself. */
    override suspend fun unavailableReason(): String? = null

    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
        val query = arguments.getValue("query")
        val notes = mutableListOf<String>()
        if (remote?.connected() == true) {
            try {
                val started =
                    remote.play(query)
                        ?: return ExecutionOutcome(
                            InvocationStatus.NOT_EXECUTED,
                            "$label found nothing for \"$query\". Nothing was played.",
                        )
                return ExecutionOutcome(
                    InvocationStatus.HANDED_OFF,
                    "$label accepted the request to play ${started.title} on ${started.device}. EVA did not watch it start.",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                notes += error.message ?: "$label's account service did not take the request."
            }
        }
        val before = observed()
        if (before?.canPlayFromSearch == true && withContext(Dispatchers.IO) { sessions.playFromSearch(packageName, query) }) {
            return confirm(query, before)
        }
        val browser = app.browser
        if (browser != null && packageName !in refusals) {
            when (launcher.playOn(browser, query)) {
                PlayDelivery.DELIVERED -> {
                    return confirm(query, null)
                }

                PlayDelivery.REFUSED -> {
                    refusals += packageName
                    notes += "$label did not accept EVA as a media client."
                }

                PlayDelivery.UNREACHABLE -> {
                    notes += "$label did not answer EVA's media service request."
                }
            }
        }
        if (intent == null) {
            notes +=
                if (before ==
                    null
                ) {
                    "$label is not running, and offers no way to start a request by name from here."
                } else {
                    "$label did not take the request."
                }
            return ExecutionOutcome(InvocationStatus.NOT_EXECUTED, notes.joinToString(" "))
        }
        intent.unavailableReason()?.let { return ExecutionOutcome(InvocationStatus.NOT_EXECUTED, (notes + it).joinToString(" ")) }
        val outcome = intent.execute(arguments)
        if (outcome.status != InvocationStatus.HANDED_OFF) return outcome
        settle()
        val after = observed() ?: return outcome
        if (started(before, after)) return MediaText.confirmStarted(label, query, after)
        if (!after.canPlayFromSearch) return outcome
        if (!withContext(Dispatchers.IO) { sessions.playFromSearch(packageName, query) }) return outcome
        return confirm(query, before ?: after)
    }

    private suspend fun observed(): MediaSnapshot? =
        withContext(Dispatchers.IO) {
            if (sessions.observable()) sessions.sessions().firstOrNull { it.packageName == packageName } else null
        }

    private suspend fun confirm(
        query: String,
        previous: MediaSnapshot?,
    ): ExecutionOutcome {
        var after: MediaSnapshot? = null
        repeat(MediaPlayBackend.CONFIRM_READS) {
            settle()
            if (!sessions.observable()) return ExecutionOutcome(InvocationStatus.HANDED_OFF, MediaText.askedToPlay(label, query))
            after = observed()
            if (after?.let { started(previous, it) } == true) return MediaText.confirmStarted(label, query, after)
        }
        return MediaText.confirmStarted(label, query, after)
    }

    private fun started(
        previous: MediaSnapshot?,
        after: MediaSnapshot,
    ): Boolean = after.playing && (previous == null || !previous.playing || previous.title != after.title)
}

/** One app's own queue route. Not connected is a setup step the user has to take, so it is reported before anything is tried. */
internal class MediaAppQueueBackend(
    private val label: String,
    private val provider: QueueProvider,
) : ExecutionBackend {
    override suspend fun unavailableReason(): String? =
        if (provider.connected()) null else "Queueing on $label needs it connected in EVA's settings first. Nothing was queued."

    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
        val query = arguments.getValue("query")
        return try {
            val track =
                provider.queue(query)
                    ?: return ExecutionOutcome(InvocationStatus.NOT_EXECUTED, "$label found nothing for \"$query\". Nothing was queued.")
            val artists = track.artists.joinToString(", ").ifBlank { "an unknown artist" }
            ExecutionOutcome(
                InvocationStatus.COMPLETED,
                "Queued \"${track.title}\" by $artists on $label. It plays after the current track.",
            )
        } catch (error: IllegalStateException) {
            ExecutionOutcome(InvocationStatus.NOT_EXECUTED, error.message ?: "$label did not accept the queue request.")
        } catch (_: IOException) {
            ExecutionOutcome(InvocationStatus.FAILED, "$label could not be reached.")
        }
    }
}

private fun notPlaying(label: String) = "$label is not playing or paused on anything right now. Nothing was sent."
