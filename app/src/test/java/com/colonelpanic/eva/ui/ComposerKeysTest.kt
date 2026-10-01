package com.colonelpanic.eva.ui

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerKeysTest {
    @Test
    fun `enter and numpad enter send on key down and consume when sending is unavailable`() {
        for (key in listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            assertEquals(ComposerKeyAction.SEND, composerKeyAction(key, true, false, true))
            assertEquals(ComposerKeyAction.CONSUME, composerKeyAction(key, true, false, false))
        }
    }

    @Test
    fun `key up shift enter and other keys do not send`() {
        for (key in listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            assertEquals(ComposerKeyAction.IGNORE, composerKeyAction(key, false, false, true))
            assertEquals(ComposerKeyAction.IGNORE, composerKeyAction(key, true, true, true))
        }
        assertEquals(ComposerKeyAction.IGNORE, composerKeyAction(KeyEvent.KEYCODE_A, true, false, true))
    }
}
