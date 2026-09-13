package com.colonelpanic.eva

/** How EVA was opened. Anything but the launcher expects listening to start without a tap. */
enum class Launch {
    MANUAL,
    HANDS_FREE,
    SECURE_HANDS_FREE,
    ;

    val startsVoice: Boolean get() = this != MANUAL

    /** Securely locked: run the session, but show nothing that was said. */
    val locked: Boolean get() = this == SECURE_HANDS_FREE
}

/**
 * A long-pressed headset button does not reach the assistant role or a voice interaction
 * service. MediaSessionService turns it into a web search when the device is open and a
 * hands-free search when it is locked, and a Bluetooth headset asking for voice recognition
 * sends a voice command, so EVA has to answer all of them to be reachable from headphones.
 */
private val HANDS_FREE_ACTIONS =
    setOf(
        "android.intent.action.ASSIST",
        "android.intent.action.VOICE_ASSIST",
        "android.intent.action.VOICE_COMMAND",
        "android.speech.action.VOICE_SEARCH_HANDS_FREE",
        "android.speech.action.WEB_SEARCH",
    )

fun launchFor(
    action: String?,
    secure: Boolean,
): Launch =
    when {
        action == null || action !in HANDS_FREE_ACTIONS -> Launch.MANUAL
        secure -> Launch.SECURE_HANDS_FREE
        else -> Launch.HANDS_FREE
    }
