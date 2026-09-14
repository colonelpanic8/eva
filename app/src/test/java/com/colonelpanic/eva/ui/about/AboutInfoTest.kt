package com.colonelpanic.eva.ui.about

import org.junit.Assert.assertEquals
import org.junit.Test

class AboutInfoTest {
    @Test
    fun `a readable package reports the name and build a report needs`() {
        assertEquals("0.11.0 (11000)", AboutInfo(version = "0.11.0", versionCode = 11_000).versionLabel)
    }

    @Test
    fun `an unreadable package says so instead of inventing a version`() {
        assertEquals("Unavailable", AboutInfo().versionLabel)
        assertEquals("0.11.0", AboutInfo(version = "0.11.0").versionLabel)
    }
}
