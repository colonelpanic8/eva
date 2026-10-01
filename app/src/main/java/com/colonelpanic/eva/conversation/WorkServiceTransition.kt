package com.colonelpanic.eva.conversation

/** Serial host transitions await work promotion before relinquishing voice coverage. */
class WorkServiceTransition(
    private val startWork: suspend () -> Boolean,
    private val stopWork: () -> Unit,
    private val startVoice: () -> Unit,
    private val stopVoice: () -> Unit,
) {
    private var voiceActive = false
    private var workActive = false

    suspend fun update(
        voice: Boolean,
        work: Boolean,
    ) {
        if (voice && !voiceActive) startVoice()
        if (work) workActive = startWork()
        if (!voice && voiceActive) stopVoice()
        if (!work && workActive) {
            stopWork()
            workActive = false
        }
        voiceActive = voice
    }
}
