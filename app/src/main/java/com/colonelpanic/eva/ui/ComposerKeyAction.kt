package com.colonelpanic.eva.ui

import android.view.KeyEvent

internal enum class ComposerKeyAction { IGNORE, CONSUME, SEND }

internal fun composerKeyAction(
    keyCode: Int,
    isKeyDown: Boolean,
    isShiftPressed: Boolean,
    canSend: Boolean,
): ComposerKeyAction {
    if (!isKeyDown || isShiftPressed || (keyCode != KeyEvent.KEYCODE_ENTER && keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER)) {
        return ComposerKeyAction.IGNORE
    }
    return if (canSend) ComposerKeyAction.SEND else ComposerKeyAction.CONSUME
}
