package com.colonelpanic.eva.assist

/** An installed speech recognizer EVA could hand a third-party recognition request to. */
internal data class RecognizerCandidate(
    val packageName: String,
    val className: String,
    val preinstalled: Boolean,
)

/**
 * Selecting a digital assistant also makes that assistant's declared recognition service the
 * device-wide speech recognizer, so a stub here would break dictation in unrelated apps. EVA
 * has no dictation engine of its own -- its own speech goes straight to a realtime provider --
 * so it forwards to another installed recognizer, preferring a preinstalled one.
 *
 * Both identifiers are excluded, not just the package: a debug build carries an
 * `applicationId` suffix, so a phone with both installed would otherwise have each variant
 * forward to the other until the recursion is cut off by something other than this function.
 */
internal fun preferredRecognizer(
    candidates: List<RecognizerCandidate>,
    selfPackage: String,
    selfClass: String,
): RecognizerCandidate? {
    val others = candidates.filter { it.packageName != selfPackage && it.className != selfClass }
    return others.firstOrNull { it.preinstalled } ?: others.firstOrNull()
}
