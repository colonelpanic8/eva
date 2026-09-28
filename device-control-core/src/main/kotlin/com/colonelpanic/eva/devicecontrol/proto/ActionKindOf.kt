package com.colonelpanic.eva.devicecontrol.proto

val Action.kind: ActionKind get() =
    when (this) {
        is LaunchApp -> ActionKind.LAUNCH_APP
        is ActivateElement -> ActionKind.ACTIVATE_ELEMENT
        is SetText -> ActionKind.SET_TEXT
        is Scroll -> ActionKind.SCROLL
        is Back -> ActionKind.BACK
        is Home -> ActionKind.HOME
        is LockScreen -> ActionKind.LOCK_SCREEN
        is TapPoint -> ActionKind.TAP_POINT
        is Swipe -> ActionKind.SWIPE
        is LongPress -> ActionKind.LONG_PRESS
        is Screenshot -> ActionKind.SCREENSHOT
        is ImeAction -> ActionKind.IME_ACTION
        is OpenUrl -> ActionKind.OPEN_URL
        is OpenNotifications -> ActionKind.OPEN_NOTIFICATIONS
    }
