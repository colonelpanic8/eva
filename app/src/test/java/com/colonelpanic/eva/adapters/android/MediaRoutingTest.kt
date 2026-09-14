package com.colonelpanic.eva.adapters.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaRoutingTest {
    private val spotify =
        MediaSnapshot(
            packageName = "com.spotify.music",
            appLabel = "Spotify",
            title = "Black Hole Sun",
            artist = "Soundgarden",
            playing = true,
            supports = setOf(MediaCommand.PAUSE, MediaCommand.NEXT),
        )
    private val podcasts =
        MediaSnapshot(
            packageName = "com.example.pods",
            appLabel = "Pocket Casts",
            title = "Episode 12",
            playing = false,
            supports = setOf(MediaCommand.PLAY),
        )

    @Test
    fun `the playing session is controlled when no app is named`() {
        val plan = MediaRouting.plan(observable = true, sessions = listOf(podcasts, spotify), app = null, command = MediaCommand.PAUSE)
        assertEquals(MediaPlan.Control(spotify), plan)
    }

    @Test
    fun `a named app is matched by label, partial label, and package`() {
        val sessions = listOf(spotify, podcasts)
        for (name in listOf("Spotify", "spot", "com.spotify")) {
            assertEquals(name, MediaPlan.Control(spotify), MediaRouting.plan(true, sessions, name, MediaCommand.PAUSE))
        }
    }

    @Test
    fun `naming an app that is not playing refuses instead of controlling another one`() {
        val plan = MediaRouting.plan(true, listOf(spotify), "YouTube Music", MediaCommand.NEXT)
        val refusal = plan as MediaPlan.Refuse
        assertTrue(refusal.message, refusal.message.contains("Spotify") && refusal.message.contains("Nothing was sent"))
    }

    @Test
    fun `without notification access the button carries the request but no app can be chosen`() {
        assertEquals(MediaPlan.MediaButton, MediaRouting.plan(false, emptyList(), null, MediaCommand.NEXT))
        assertEquals(
            MediaPlan.Refuse(MediaRouting.NEEDS_ACCESS_TO_CHOOSE),
            MediaRouting.plan(false, emptyList(), "Spotify", MediaCommand.NEXT),
        )
    }

    @Test
    fun `with nothing playing only resuming is attempted`() {
        assertEquals(MediaPlan.MediaButton, MediaRouting.plan(true, emptyList(), null, MediaCommand.TOGGLE))
        assertEquals(MediaPlan.Refuse(MediaRouting.NOTHING_PLAYING), MediaRouting.plan(true, emptyList(), null, MediaCommand.NEXT))
    }

    @Test
    fun `a session refuses only an action it declares it does not take`() {
        assertTrue(MediaRouting.plan(true, listOf(spotify), null, MediaCommand.PREVIOUS) is MediaPlan.Refuse)
        val silent = spotify.copy(supports = emptySet())
        assertEquals(MediaPlan.Control(silent), MediaRouting.plan(true, listOf(silent), null, MediaCommand.PREVIOUS))
    }

    @Test
    fun `a toggle becomes the command the session's own state calls for`() {
        assertEquals(MediaCommand.PAUSE, MediaCommand.TOGGLE.resolve(spotify))
        assertEquals(MediaCommand.PLAY, MediaCommand.TOGGLE.resolve(podcasts))
        assertEquals(MediaCommand.NEXT, MediaCommand.NEXT.resolve(spotify))
    }
}
