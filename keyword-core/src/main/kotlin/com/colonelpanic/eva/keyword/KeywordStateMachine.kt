package com.colonelpanic.eva.keyword

import com.colonelpanic.eva.audio.VoiceEvent
import com.colonelpanic.eva.audio.VoiceFlags
import com.colonelpanic.eva.audio.notice
import java.util.concurrent.atomic.AtomicLong

/** The user-facing mode; connection and task activity are tracked separately in [VoiceFlags]. */
enum class KeywordState {
    ASLEEP,
    LISTENING,
    MUTED,
    STOPPED,
    ;

    companion object {
        fun of(flags: VoiceFlags): KeywordState =
            when {
                flags.stopped -> STOPPED
                flags.asleep -> ASLEEP
                flags.muted -> MUTED
                else -> LISTENING
            }
    }
}

/** Which spotter phrase fills which role. Mute and unmute may share a phrase (a toggle); stop may not share. */
data class PhraseRoles(
    val wake: String? = null,
    val stop: String? = null,
    val mute: String? = null,
    val unmute: String? = null,
) {
    init {
        require(stop == null || stop !in setOf(wake, mute, unmute)) { "the stop phrase must be distinct from the others" }
        require(wake == null || wake != mute) { "the wake and mute phrases must differ" }
    }

    val all: Set<String> get() = setOfNotNull(wake, stop, mute, unmute)
}

enum class Origin(
    val announces: Boolean,
) {
    KEYWORD(true),
    LOCAL(true),
    IDLE(true),
    GATE(true),
    HOST(false),
    LINK(false),
}

data class StopTiming(
    val detectedAtNanos: Long,
    val latchedAtNanos: Long,
    val speechOnsetNanos: Long?,
) {
    val detectionToLatchNanos: Long get() = latchedAtNanos - detectedAtNanos
}

data class Transition(
    val before: VoiceFlags,
    val after: VoiceFlags,
    val event: VoiceEvent,
    val origin: Origin,
)

/**
 * The keyword layer's state machine over [VoiceFlags]; every state change in the voice link goes
 * through it, whatever its origin.
 *
 * - Stop phrase, any state: the local [StopLatch] is set before anything else, then the host is
 *   told. Detection to latch is recorded in [lastStop].
 * - Wake phrase: asleep → listening (requests a voice attachment through [VoiceSessionControl]); from stopped it also
 *   resumes the latch.
 * - Mute / unmute phrases: listening ↔ muted. A running task keeps running.
 * - Idle: listening or stopped → asleep after [idleTimeoutMs] with no speech, keyword, or host
 *   audio, unless a task is working or an announcement plays; sleep requests attachment closure.
 * - Mute, sleep, and stop end an announcement ([announce]); a stop still latches first.
 *
 * The execution gate's latch is the one stop state: stopped is shown exactly while it is set,
 * whoever set or cleared it ([onGate]). Local stops and resumes set and clear the latch here;
 * a host re-handshake may clear only a latch the host alone set.
 *
 * The uplink's audio gate is closed, under the same lock, before any state that does not send
 * audio becomes visible, and opened only through [openAudioIfAllowed]. The host can only take
 * audio away. Nothing here can approve anything: its only outputs are the stop latch, the audio
 * gate, voice attachment requests, and the six notices mute, unmute, sleep, wake, stop, and resume.
 */
class KeywordStateMachine(
    val roles: PhraseRoles,
    private val latch: StopLatch,
    private val audioGate: AudioTransmissionGate,
    private val session: VoiceSessionControl,
    private val notices: KeywordNotices,
    private val clock: () -> Long,
    idleTimeoutMs: Long? = DEFAULT_IDLE_TIMEOUT_MS,
    startAsleep: Boolean = true,
    private val log: (String) -> Unit = {},
    private val onTransition: (Transition) -> Unit = {},
) {
    private val lock = Any()
    private val idleTimeoutNanos = idleTimeoutMs?.let { it * 1_000_000 }
    private val lastActivityNanos = AtomicLong(clock())
    private var deviceStopSeen = false

    @Volatile var flags = VoiceFlags(asleep = startAsleep)
        private set

    @Volatile var lastStop: StopTiming? = null
        private set

    val state: KeywordState get() = KeywordState.of(flags)

    val sendsAudio: Boolean get() = flags.state.sendsAudio

    fun onKeyword(event: KeywordEvent): List<VoiceEvent> {
        if (event.phrase == roles.stop) {
            latch.stop(StopSource.KEYWORD)
            val timing = StopTiming(event.detectedAtNanos, clock(), event.speechOnsetNanos)
            lastStop = timing
            log(
                "keyword stop latched ${timing.detectionToLatchNanos / 1_000} µs after detection" +
                    (event.speechOnsetNanos?.let { "; ${(event.detectedAtNanos - it) / 1_000_000} ms after speech onset" } ?: ""),
            )
            run(Origin.KEYWORD, listOf(VoiceEvent.Stop))
            return listOf(VoiceEvent.Stop)
        }
        if (event.phrase in roles.all) onActivity(event.detectedAtNanos)
        val transitions =
            synchronized(lock) {
                resolve(event.phrase, flags).mapNotNull { applyLocked(it, Origin.KEYWORD) }
            }
        transitions.forEach(onTransition)
        return transitions.map { it.event }
    }

    /** A notification action or other local input ([Origin.LOCAL]), a host control, or link status. */
    fun handle(
        event: VoiceEvent,
        origin: Origin,
    ) {
        run(origin, listOf(event))
    }

    /**
     * The coordinator's latch changed, from here or another local or remote control. The audio host hears of a stop or resume
     * only when a device-side source was involved; it already knows of its own.
     */
    fun onGate(
        stopped: Boolean,
        source: StopSource?,
    ) {
        val transition =
            synchronized(lock) {
                if (stopped) {
                    if (source != StopSource.HOST) deviceStopSeen = true
                    if (flags.stopped) null else applyLocked(VoiceEvent.Stop, Origin.GATE, announce = source != StopSource.HOST)
                } else {
                    val announce = deviceStopSeen
                    deviceStopSeen = false
                    if (!flags.stopped) null else applyLocked(VoiceEvent.Resume, Origin.GATE, announce = announce)
                }
            }
        transition?.let(onTransition)
    }

    /**
     * An announcement starts or ends. It can start only while audio flows both ways, which is
     * checked under the same lock as mute, sleep, and stop, so one of those arriving concurrently
     * either refuses it here or ends it with a transition. True when [active] now holds.
     */
    fun announce(active: Boolean): Boolean {
        val transition =
            synchronized(lock) {
                val current = flags
                if (current.announcing == active) return true
                if (active && !current.state.sendsAudio) return false
                applyLocked(VoiceEvent.Announce(active), Origin.HOST)
            }
        transition?.let(onTransition)
        return true
    }

    fun onActivity(atNanos: Long) {
        lastActivityNanos.accumulateAndGet(atNanos, ::maxOf)
    }

    /** Puts the device to sleep if it has been idle for the timeout; true when it did. */
    fun tick(now: Long = clock()): Boolean {
        val timeout = idleTimeoutNanos ?: return false
        val transition =
            synchronized(lock) {
                val current = flags
                if (current.asleep || current.muted || current.working || current.announcing) return false
                if (now - lastActivityNanos.get() < timeout) return false
                applyLocked(VoiceEvent.Sleep, Origin.IDLE)
            }
        transition?.let(onTransition)
        return transition != null
    }

    /** Opens the uplink's audio gate if the current state sends audio; call once the recorder runs. */
    fun openAudioIfAllowed(): Boolean =
        synchronized(lock) {
            flags.state.sendsAudio.also { if (it) audioGate.setAudioAllowed(true) }
        }

    private fun run(
        origin: Origin,
        events: List<VoiceEvent>,
    ) {
        val transitions = synchronized(lock) { events.mapNotNull { applyLocked(it, origin) } }
        transitions.forEach(onTransition)
    }

    private fun resolve(
        phrase: String,
        current: VoiceFlags,
    ): List<VoiceEvent> =
        when {
            phrase == roles.unmute && current.muted -> listOf(VoiceEvent.Unmute)
            phrase == roles.mute && !current.muted && !current.asleep && !current.stopped -> listOf(VoiceEvent.Mute)
            phrase == roles.wake && current.stopped -> listOfNotNull(VoiceEvent.Resume, VoiceEvent.Wake.takeIf { current.asleep })
            phrase == roles.wake && current.asleep -> listOf(VoiceEvent.Wake)
            else -> emptyList()
        }

    private fun applyLocked(
        event: VoiceEvent,
        origin: Origin,
        announce: Boolean = origin.announces,
    ): Transition? {
        if (origin == Origin.HOST && event in HOST_FORBIDDEN) return null
        when (event) {
            VoiceEvent.Stop -> {
                if (origin != Origin.KEYWORD && origin != Origin.GATE) latch.stop(stopSource(origin))
                if (origin != Origin.HOST) deviceStopSeen = true
            }

            VoiceEvent.Resume -> {
                if (origin != Origin.GATE) latch.resume()
                deviceStopSeen = false
            }

            else -> Unit
        }
        val before = flags
        val after = before.on(event)
        if (!after.state.sendsAudio) audioGate.setAudioAllowed(false)
        flags = after
        if (event == VoiceEvent.Wake && before.asleep && !after.asleep) session.wake()
        if (event == VoiceEvent.Sleep && !before.asleep && after.asleep) session.sleep()
        if (announce) notice(event)?.let(notices::notify)
        if (event in ACTIVITY || (event is VoiceEvent.Task && !event.working)) onActivity(clock())
        return Transition(before, after, event, origin)
    }

    private fun stopSource(origin: Origin) =
        when (origin) {
            Origin.HOST -> StopSource.HOST
            Origin.KEYWORD -> StopSource.KEYWORD
            else -> StopSource.NOTIFICATION
        }

    companion object {
        const val DEFAULT_IDLE_TIMEOUT_MS = 60_000L
        private val HOST_FORBIDDEN = setOf(VoiceEvent.Unmute, VoiceEvent.Wake, VoiceEvent.Resume)
        private val ACTIVITY = setOf(VoiceEvent.Wake, VoiceEvent.Unmute, VoiceEvent.Resume, VoiceEvent.LinkUp)
    }
}
