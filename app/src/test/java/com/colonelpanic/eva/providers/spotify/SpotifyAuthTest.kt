package com.colonelpanic.eva.providers.spotify

import org.junit.Assert.assertEquals
import org.junit.Test

class SpotifyAuthTest {
    @Test
    fun `code challenge matches the RFC 7636 appendix B vector`() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            spotifyCodeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }
}
