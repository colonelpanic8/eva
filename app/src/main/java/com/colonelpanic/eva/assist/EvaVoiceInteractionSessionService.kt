package com.colonelpanic.eva.assist

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** Builds one [EvaVoiceInteractionSession] per assistant invocation. */
class EvaVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = EvaVoiceInteractionSession(this)
}
