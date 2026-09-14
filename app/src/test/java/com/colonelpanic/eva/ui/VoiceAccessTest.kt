package com.colonelpanic.eva.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceAccessTest {
    private val link = "http://127.0.0.1:45944/#code"

    @Test
    fun `a granted microphone connects immediately`() {
        val access = VoiceAccess()
        assertEquals(VoiceStart.Connect(link), access.start(link, microphoneGranted = true))
        assertNull(access.denial)
        assertNull(access.onPermissionResult(granted = true, canAskAgain = true))
    }

    @Test
    fun `a granted request connects with the remembered link`() {
        val access = VoiceAccess()
        assertEquals(VoiceStart.RequestMicrophone(link), access.start(link, microphoneGranted = false))
        assertEquals(VoiceStart.Connect(link), access.onPermissionResult(granted = true, canAskAgain = true))
        assertNull(access.onPermissionResult(granted = true, canAskAgain = true))
    }

    @Test
    fun `a denial keeps the link for retry or settings until dismissed`() {
        val access = VoiceAccess()
        access.start(link, microphoneGranted = false)
        assertNull(access.onPermissionResult(granted = false, canAskAgain = true))
        assertEquals(MicrophoneDenial(link, canAskAgain = true), access.denial)

        assertEquals(VoiceStart.RequestMicrophone(link), access.retry(microphoneGranted = false))
        assertNull(access.denial)
        assertNull(access.onPermissionResult(granted = false, canAskAgain = false))
        assertEquals(MicrophoneDenial(link, canAskAgain = false), access.denial)

        assertNull(access.retry(microphoneGranted = false))
        assertEquals(MicrophoneDenial(link, canAskAgain = false), access.denial)
        assertEquals(VoiceStart.Connect(link), access.retry(microphoneGranted = true))
        assertNull(access.denial)

        access.start(link, microphoneGranted = false)
        access.onPermissionResult(granted = false, canAskAgain = true)
        access.dismiss()
        assertNull(access.denial)
        assertNull(access.retry(microphoneGranted = true))
    }

    @Test
    fun `starting again replaces a pending request and clears a denial`() {
        val access = VoiceAccess()
        access.start("first", microphoneGranted = false)
        access.onPermissionResult(granted = false, canAskAgain = true)
        assertEquals(VoiceStart.RequestMicrophone("second"), access.start("second", microphoneGranted = false))
        assertNull(access.denial)
        assertEquals(VoiceStart.Connect("second"), access.onPermissionResult(granted = true, canAskAgain = true))
    }
}
