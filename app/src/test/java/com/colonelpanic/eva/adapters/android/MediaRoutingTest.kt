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
    fun `the playing session is controlled`() {
        val plan = MediaRouting.plan(observable = true, sessions = listOf(podcasts, spotify), command = MediaCommand.PAUSE)
        assertEquals(MediaPlan.Control(spotify), plan)
    }

    @Test
    fun `without notification access the button carries the request unseen`() {
        assertEquals(MediaPlan.MediaButton, MediaRouting.plan(false, emptyList(), MediaCommand.NEXT))
    }

    @Test
    fun `with nothing playing only resuming is attempted`() {
        assertEquals(MediaPlan.MediaButton, MediaRouting.plan(true, emptyList(), MediaCommand.TOGGLE))
        assertEquals(MediaPlan.Refuse(MediaRouting.NOTHING_PLAYING), MediaRouting.plan(true, emptyList(), MediaCommand.NEXT))
    }

    @Test
    fun `a session refuses only an action it declares it does not take`() {
        assertTrue(MediaRouting.plan(true, listOf(spotify), MediaCommand.PREVIOUS) is MediaPlan.Refuse)
        val silent = spotify.copy(supports = emptySet())
        assertEquals(MediaPlan.Control(silent), MediaRouting.plan(true, listOf(silent), MediaCommand.PREVIOUS))
    }

    @Test
    fun `a toggle becomes the command the session's own state calls for`() {
        assertEquals(MediaCommand.PAUSE, MediaCommand.TOGGLE.resolve(spotify))
        assertEquals(MediaCommand.PLAY, MediaCommand.TOGGLE.resolve(podcasts))
        assertEquals(MediaCommand.NEXT, MediaCommand.NEXT.resolve(spotify))
    }
}
