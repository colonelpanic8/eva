package com.colonelpanic.eva.adapters.android

import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.MemoryInvocationRepository
import com.colonelpanic.eva.capability.ToolProposal
import com.colonelpanic.eva.capability.extensions.ExtensionGrants
import com.colonelpanic.eva.capability.extensions.ExtensionRuntime
import com.colonelpanic.eva.capability.extensions.MemoryGrantPersistence
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeSource(
    var apps: List<DiscoveredMediaApp>,
) : MediaAppSource {
    override fun scan() = apps
}

private class FakeLauncher(
    private val delivery: PlayDelivery,
) : MediaLauncher {
    val asked = mutableListOf<Pair<MediaApp, String>>()

    override fun browsableApps() = emptyList<MediaApp>()

    override suspend fun playOn(
        target: MediaApp,
        query: String,
    ): PlayDelivery {
        asked += target to query
        return delivery
    }
}

private class FakeQueue(
    var connected: Boolean,
    private val track: QueuedTrack? = QueuedTrack("Black Hole Sun", listOf("Soundgarden")),
) : QueueProvider {
    override val label = "Spotify"

    override fun connected() = connected

    override suspend fun queue(query: String) = track
}

private class FakeIntent : ExecutionBackend {
    var executed = 0

    override suspend fun unavailableReason(): String? = null

    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
        executed++
        return ExecutionOutcome(InvocationStatus.HANDED_OFF, "Asked the app to play that.")
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MediaAdapterTest {
    private val youtube =
        DiscoveredMediaApp(
            MediaIdentity(0, "com.google.android.youtube", "sig", 1L),
            "YouTube",
            browser = null,
            library = null,
            handlesSearchIntent = true,
        )
    private val spotify =
        DiscoveredMediaApp(
            MediaIdentity(0, "com.spotify.music", "sig", 1L),
            "Spotify",
            browser = MediaApp("com.spotify.music", "Spotify", "com.spotify.MediaService"),
            library = null,
            handlesSearchIntent = true,
        )
    private val podcasts =
        DiscoveredMediaApp(
            MediaIdentity(0, "com.example.pods", "sig", 1L),
            "Pocket Casts",
            browser = MediaApp("com.example.pods", "Pocket Casts", "com.example.MediaService"),
            library = MediaLibraryApp("com.example.pods", "Pocket Casts", "com.example.LibraryService"),
            handlesSearchIntent = false,
        )
    private val sessionOnly =
        DiscoveredMediaApp(MediaIdentity(0, "com.example.tab", "sig", 1L), "Some Player", null, null, handlesSearchIntent = false)

    private fun adapter(
        source: MediaAppSource,
        sessions: MediaSessionAccess = FakeMediaSessions(mutableListOf(emptyList())),
        launcher: MediaLauncher = FakeLauncher(PlayDelivery.DELIVERED),
        queue: FakeQueue? = null,
        intent: ExecutionBackend? = null,
    ) = MediaAdapter(
        source,
        sessions,
        launcher,
        queueFor = { app -> queue?.takeIf { app.identity.packageName == "com.spotify.music" || app.library != null } },
        intentFor = { app -> intent?.takeIf { app.handlesSearchIntent } },
        settle = {},
    )

    private fun MediaAdapter.entry(app: DiscoveredMediaApp) = installed.value.single { it.identity == app.identity }

    private fun MediaAdapter.backend(
        app: DiscoveredMediaApp,
        name: String,
    ) = bindings(entry(app)).single { it.capability.name == name }.backend

    @Test
    fun `each app is offered only the operations it has a route for`() {
        val adapter = adapter(FakeSource(listOf(youtube, spotify, podcasts, sessionOnly)), queue = FakeQueue(connected = true))
        adapter.refresh()
        val names = { app: DiscoveredMediaApp ->
            adapter
                .entry(app)
                .descriptor!!
                .capabilities
                .map { it.name }
        }
        assertEquals(listOf("control", "now_playing", "play"), names(youtube))
        assertEquals(listOf("control", "now_playing", "play", "queue"), names(spotify))
        assertEquals(listOf("control", "now_playing", "play", "queue"), names(podcasts))
        assertEquals(listOf("control", "now_playing", "play"), names(sessionOnly))

        val ids = adapter.bindings(adapter.entry(youtube)).map { it.definition.id }
        assertEquals(
            listOf(
                "extension.media.com.google.android.youtube.control",
                "extension.media.com.google.android.youtube.now_playing",
                "extension.media.com.google.android.youtube.play",
            ),
            ids,
        )
        val play = { app: DiscoveredMediaApp ->
            adapter
                .entry(app)
                .descriptor!!
                .capabilities
                .single { it.name == "play" }
                .description
        }
        assertTrue(play(youtube).contains("starting it if it is not running"))
        assertTrue(play(sessionOnly).contains("Only works while Some Player is already running"))
        // Only the operation set is in the digest, so a label change cannot revoke a grant.
        assertEquals(adapter.entry(youtube).descriptor!!.digest, adapter.entry(sessionOnly).descriptor!!.digest)
    }

    @Test
    fun `a media app is admitted and granted operation by operation like any extension`() =
        runTest {
            val adapter = adapter(FakeSource(listOf(youtube)))
            val registry = CapabilityRegistry(emptyMap())
            val runtime = ExtensionRuntime(registry, adapter, ExtensionGrants(MemoryGrantPersistence()), backgroundScope)
            runCurrent()
            assertTrue(registry.catalog.isEmpty())
            val key =
                runtime.settings.value.entries
                    .single()
                    .key

            runtime.enable(key, true)
            runCurrent()
            // Enabling admits the read; each mutation stays out of the catalog until it is granted itself.
            assertEquals(listOf("extension.media.com.google.android.youtube.now_playing"), registry.catalog.map { it.id })

            runtime.mutation(key, "play", true)
            runCurrent()
            assertEquals(
                listOf("extension.media.com.google.android.youtube.now_playing", "extension.media.com.google.android.youtube.play"),
                registry.catalog.map { it.id }.sorted(),
            )

            val dispatcher = CapabilityDispatcher(registry, MemoryInvocationRepository())
            val read = registry.catalog.single { it.id.endsWith("now_playing") }
            val record = dispatcher.execute(ToolProposal("first", read.id, emptyMap(), "read", registry.snapshot.revision))
            assertEquals(InvocationStatus.COMPLETED, record.status)
            assertEquals(read.source, record.provenance!!.source)
            assertEquals("YouTube is not playing or paused on anything right now. Nothing was sent.", record.message)

            runtime.enable(key, false)
            runCurrent()
            assertTrue(registry.catalog.isEmpty())
        }

    @Test
    fun `queueing is refused with a setup reason until the provider is connected`() =
        runTest {
            val queue = FakeQueue(connected = false)
            val adapter = adapter(FakeSource(listOf(spotify)), queue = queue)
            adapter.refresh()
            val backend = adapter.backend(spotify, "queue")
            assertEquals("Queueing on Spotify needs it connected in EVA's settings first. Nothing was queued.", backend.unavailableReason())

            queue.connected = true
            assertNull(backend.unavailableReason())
            val outcome = backend.execute(mapOf("query" to "black hole sun"))
            assertEquals(InvocationStatus.COMPLETED, outcome.status)
            assertEquals("Queued \"Black Hole Sun\" by Soundgarden on Spotify. It plays after the current track.", outcome.message)
        }

    @Test
    fun `an app that turns EVA away is not asked again and the next play goes straight to the fallback`() =
        runTest {
            val launcher = FakeLauncher(PlayDelivery.REFUSED)
            val intent = FakeIntent()
            val adapter = adapter(FakeSource(listOf(spotify)), launcher = launcher, intent = intent)
            adapter.refresh()
            val play = adapter.backend(spotify, "play")

            val first = play.execute(mapOf("query" to "black hole sun"))
            assertEquals(InvocationStatus.HANDED_OFF, first.status)
            assertEquals(1, launcher.asked.size)
            assertEquals(1, intent.executed)

            play.execute(mapOf("query" to "fell on black days"))
            assertEquals(1, launcher.asked.size)
            assertEquals(2, intent.executed)
        }

    @Test
    fun `an app with a live session is asked through it and answered with what started`() =
        runTest {
            val idle = MediaSnapshot("com.google.android.youtube", "YouTube", playing = false, canPlayFromSearch = true)
            val playing = idle.copy(title = "Black Hole Sun", artist = "Soundgarden", playing = true)
            val sessions = FakeMediaSessions(mutableListOf(listOf(idle), listOf(playing)))
            val intent = FakeIntent()
            val adapter = adapter(FakeSource(listOf(youtube)), sessions = sessions, intent = intent)
            adapter.refresh()

            val outcome = adapter.backend(youtube, "play").execute(mapOf("query" to "black hole sun"))
            assertEquals(InvocationStatus.COMPLETED, outcome.status)
            assertEquals(listOf("com.google.android.youtube" to "black hole sun"), sessions.searches)
            assertEquals(0, intent.executed)
            assertTrue(outcome.message.contains("YouTube's match for \"black hole sun\""))
        }

    @Test
    fun `an app that takes the request but plays nothing is not reported as playing`() =
        runTest {
            val adapter = adapter(FakeSource(listOf(podcasts)), launcher = FakeLauncher(PlayDelivery.DELIVERED))
            adapter.refresh()
            val outcome = adapter.backend(podcasts, "play").execute(mapOf("query" to "obscure"))
            assertEquals(InvocationStatus.UNKNOWN, outcome.status)
            assertTrue(outcome.message, outcome.message.contains("not publishing any playback"))
        }

    @Test
    fun `an app that is not running cannot be controlled and says so`() =
        runTest {
            val adapter = adapter(FakeSource(listOf(youtube)))
            adapter.refresh()
            val outcome = adapter.backend(youtube, "control").execute(mapOf("action" to "pause"))
            assertEquals(InvocationStatus.NOT_EXECUTED, outcome.status)
            assertEquals("YouTube is not playing or paused on anything right now. Nothing was sent.", outcome.message)
        }

    @Test
    fun `refreshing and invalidation both read the installed apps again`() {
        val source = FakeSource(listOf(youtube))
        val adapter = adapter(source)
        adapter.refresh()
        assertEquals(1, adapter.installed.value.size)
        val digest = adapter.entry(youtube).descriptor!!.digest
        assertTrue(adapter.available(youtube.identity, digest))
        assertFalse(adapter.available(youtube.identity, "stale"))

        source.apps = listOf(youtube, podcasts)
        adapter.invalidate("com.example.pods", removed = false)
        assertEquals(2, adapter.installed.value.size)
    }
}
