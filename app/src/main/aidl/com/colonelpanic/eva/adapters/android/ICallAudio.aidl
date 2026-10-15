package com.colonelpanic.eva.adapters.android;

/**
 * Call audio through the platform's call audio redirection, executed by a Shizuku user service
 * running as shell, which holds CALL_AUDIO_INTERCEPTION on devices that grant it. A cellular call
 * (audio mode IN_CALL) is intercepted at the telephony HAL; a VoIP call (IN_COMMUNICATION) is
 * redirected through an audio policy loopback of voice-communication playback and capture.
 */
interface ICallAudio {
    void destroy() = 16777114;
    /** Whether the platform exposes PSTN call audio to interception at all. */
    boolean interceptable() = 1;
    /** {"mode":…, "calls":[{"foreground":…, "disconnectCause":…, "preciseDisconnectCause":…}]} */
    String callState() = 2;
    /**
     * One end of a socket pair carrying 16-bit little-endian mono PCM at [sampleRateHz]: the call
     * downlink arrives on it and whatever EVA writes is injected into the uplink. Closing it, or
     * the audio mode changing, stops both directions. Fails unless a cellular or VoIP call is in
     * progress. For VoIP, the remote side no longer plays on the phone and the injection replaces
     * the calling app's microphone.
     */
    ParcelFileDescriptor openAudio(int sampleRateHz) = 3;
    /** Ends the foreground call. */
    void endCall() = 4;
    /** Mutes or unmutes the phone's own microphone in the call. */
    void setMicrophoneMuted(boolean muted) = 5;
    /**
     * Allows [packageName] the MANAGE_ONGOING_CALLS app op so Telecom binds its non-UI in-call
     * service for later calls. Returns null on success, otherwise the appops command's output.
     */
    String allowCallControl(String packageName) = 6;
    /**
     * Whether what EVA writes replaces the call app's microphone. Off, the app hears the phone's own
     * microphone again; writes are discarded. Only a VoIP redirection can give the microphone back.
     */
    void setUplinkInjection(boolean enabled) = 7;
    /**
     * Starts an ACTION_VIEW activity for [uri] of [mimeType] in [packageName] as shell, which may
     * start activities from the background. Returns null on success, otherwise am's output.
     */
    String startView(String uri, String mimeType, String packageName) = 8;
}
