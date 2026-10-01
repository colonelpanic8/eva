package com.colonelpanic.eva.conversation

/** Serial host transitions await work promotion before relinquishing voice coverage. */
class WorkServiceTransition(
    private val startWork: suspend () -> Boolean,
    private val stopWork: () -> Unit,
    private val startVoice: () -> Unit,
    private val stopVoice: () -> Unit,
) {
    private var voiceActive = false

    suspend fun update(
        voice: Boolean,
        work: Boolean,
    ) {
        if (voice && !voiceActive) startVoice()
        if ((voice && !voiceActive) || (!voice && work)) startWork()
        if (!voice && voiceActive) stopVoice()
        if (!voice && !work) {
            stopWork()
        }
        voiceActive = voice
    }
}
