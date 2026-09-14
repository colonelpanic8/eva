package com.colonelpanic.eva.audio.webrtc

import android.content.Context
import android.media.AudioManager
import com.colonelpanic.eva.audio.AndroidAudioRoute
import com.colonelpanic.eva.audio.MicrophonePermission
import com.colonelpanic.eva.audio.PeerLinkFactory
import com.colonelpanic.eva.audio.RealtimeMediaConfig
import com.colonelpanic.eva.audio.RealtimeMediaController
import com.colonelpanic.eva.audio.RealtimeMediaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Creates native realtime media sessions. Hold one instance per process: WebRTC global
 * initialization and the audio device module are created lazily on first use.
 */
class WebRtcMediaSessionFactory(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val appContext = context.applicationContext

    private val peerConnectionFactory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(appContext)
                .createInitializationOptions(),
        )
        val audioDeviceModule =
            JavaAudioDeviceModule
                .builder(appContext)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule()
        try {
            PeerConnectionFactory
                .builder()
                .setAudioDeviceModule(audioDeviceModule)
                .createPeerConnectionFactory()
        } finally {
            audioDeviceModule.release()
        }
    }

    fun create(config: RealtimeMediaConfig): RealtimeMediaSession {
        val audioManager = checkNotNull(appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
        return RealtimeMediaController(
            config = config,
            peerFactory = PeerLinkFactory { WebRtcPeerLink(peerConnectionFactory) },
            route = AndroidAudioRoute(audioManager),
            microphoneGranted = { MicrophonePermission.isGranted(appContext) },
            parentScope = scope,
        )
    }
}
