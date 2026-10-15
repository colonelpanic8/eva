package com.colonelpanic.eva.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwilioAccessTokenTest {
    @Test fun mintsTheSameTokenAsTwiliosServerLibrary() {
        // twilio-python AccessToken(identity="eva", ttl=3600) + VoiceGrant(outgoing_application_sid=...) at 1760000000.
        val expected =
            "eyJhbGciOiJIUzI1NiIsImN0eSI6InR3aWxpby1mcGE7dj0xIiwidHlwIjoiSldUIn0." +
                "eyJqdGkiOiJTS2JiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiLTE3NjAwMDAwMDAiLCJncmFudHMiOnsidm9pY2UiOnsib3V0Z29p" +
                "bmciOnsiYXBwbGljYXRpb25fc2lkIjoiQVBjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjY2NjYyJ9fSwiaWRlbnRpdHkiOiJldmEifSwi" +
                "aXNzIjoiU0tiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYiIsImV4cCI6MTc2MDAwMzYwMCwibmJmIjoxNzYwMDAwMDAwLCJzdWIi" +
                "OiJBQ2FhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhIn0.GRM6T5AJUiAj0b2YK3y5-yMoPQeDamcyHuWhPQhD32s"
        val token =
            TwilioAccessToken.mint(
                accountSid = "AC" + "a".repeat(32),
                key = TwilioApiKey("SK" + "b".repeat(32), "test-secret"),
                applicationSid = "AP" + "c".repeat(32),
                identity = "eva",
                nowSeconds = 1_760_000_000,
            )
        assertEquals(expected, token)
    }

    @Test fun anApiKeyNeverPrintsItsSecret() {
        val key = TwilioApiKey("SK" + "b".repeat(32), "test-secret")
        assertFalse("test-secret" in key.toString())
        assertTrue(runCatching { TwilioApiKey("AC" + "b".repeat(32), "x") }.isFailure)
    }
}
