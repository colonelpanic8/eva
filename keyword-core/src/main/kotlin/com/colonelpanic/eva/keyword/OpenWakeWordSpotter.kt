package com.colonelpanic.eva.keyword

import com.colonelpanic.eva.audio.FrameRingBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SpotterStats(
    val chunks: Long,
    val droppedFrames: Long,
    val gatedDetections: Long,
    val lastChunkNanos: Long,
    val maxChunkNanos: Long,
)

/**
 * openWakeWord keyword spotter. Frames at any capture rate are resampled to 16 kHz and scored
 * every 80 ms by every phrase in [specs]; see [OpenWakeWordPipeline] and [PhraseDetector].
 *
 * [start] copies frames into a bounded queue (oldest dropped and counted when inference falls
 * behind) that one dedicated thread drains. [process] is the same work done synchronously, for
 * offline evaluation. [onScores] sees every chunk's raw scores on the spotter thread.
 */
class OpenWakeWordSpotter(
    private val models: WakeWordModels,
    specs: List<PhraseSpec>,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    queueFrames: Int = 50,
    private val onScores: (Map<String, Float>) -> Unit = {},
) : KeywordSpotter {
    private val detectors = specs.map(::PhraseDetector)
    private val pipeline = OpenWakeWordPipeline(models)
    private val activity = SpeechActivity()
    private val queue = FrameRingBuffer<PcmFrame>(queueFrames)
    private var resampler: PolyphaseResampler? = null
    private var samplesIn = 0L
    private var collector: Job? = null
    private var thread: Thread? = null
    private var closed = false

    @Volatile private var running = false

    @Volatile private var stats = SpotterStats(0, 0, 0, 0, 0)

    private val mutableEvents = MutableSharedFlow<KeywordEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val mutableStatus = MutableStateFlow<SpotterStatus>(SpotterStatus.Idle)
    private val mutableSpeech = MutableStateFlow<Long?>(null)

    init {
        val unknown = specs.map { it.id } - models.phrases
        require(unknown.isEmpty()) { "no model for ${unknown.joinToString()}" }
        require(specs.map { it.id }.toSet().size == specs.size) { "duplicate phrase" }
    }

    override val events: SharedFlow<KeywordEvent> = mutableEvents.asSharedFlow()
    override val status: StateFlow<SpotterStatus> = mutableStatus.asStateFlow()
    override val lastSpeechNanos: StateFlow<Long?> = mutableSpeech.asStateFlow()
    override val phrases: Set<String> = specs.map { it.id }.toSet()

    fun stats(): SpotterStats = stats.copy(droppedFrames = queue.dropped)

    override fun start(frames: Flow<PcmFrame>) {
        synchronized(this) {
            check(!closed) { "spotter is closed" }
            if (running) return
            running = true
            mutableStatus.value = SpotterStatus.Running
            collector =
                scope.launch {
                    frames.collect { frame ->
                        queue.offer(PcmFrame(frame.samples.copyOf(), frame.sampleRate, frame.capturedAtNanos))
                    }
                }
            thread =
                Thread(::drain, "keyword-spotter").also {
                    it.priority = Thread.MAX_PRIORITY
                    it.start()
                }
        }
    }

    /** Waits for in-flight inference; call from the lifecycle worker, never the capture callback. */
    override fun close() {
        val stopping: Thread?
        synchronized(this) {
            if (closed) return
            closed = true
            running = false
            collector?.cancel()
            collector = null
            stopping = thread
            thread = null
        }
        // Never close native sessions while inference is still using them.
        stopping?.join()
        queue.clear()
        models.close()
        mutableStatus.value = SpotterStatus.Idle
    }

    /** Runs one frame through the resampler, speech activity, and every phrase; returns detections. */
    @Synchronized
    fun process(frame: PcmFrame): List<KeywordEvent> {
        check(!closed) { "spotter is closed" }
        val converter =
            resampler?.takeIf { it.inRate == frame.sampleRate }
                ?: PolyphaseResampler(frame.sampleRate, OpenWakeWordPipeline.SAMPLE_RATE).also { resampler = it }
        val input = converter.process(frame.samples)
        samplesIn += input.size
        val frameEndSample = samplesIn
        activity.accept(input, frame.capturedAtNanos)
        activity.lastSpeechNanos?.let { if (it != mutableSpeech.value) mutableSpeech.value = it }
        val started = System.nanoTime()
        val chunks = pipeline.accept(input)
        if (chunks.isEmpty()) return emptyList()
        val elapsed = System.nanoTime() - started
        var gated = 0L
        val detected = mutableListOf<KeywordEvent>()
        for (chunk in chunks) {
            onScores(chunk.scores)
            val chunkNanos = frame.capturedAtNanos - (frameEndSample - chunk.endSample) * SAMPLE_NANOS
            for (detector in detectors) {
                val spec = detector.spec
                val score = chunk.scores.getValue(spec.id)
                val vetoed = score >= spec.threshold && spec.burstGate?.passes(activity, chunkNanos) == false
                if (vetoed) gated++
                if (detector.offer(if (vetoed) 0f else score, chunkNanos)) {
                    detected += KeywordEvent(spec.id, score, clock(), activity.onsetBefore(chunkNanos))
                }
            }
        }
        val previous = stats
        stats =
            previous.copy(
                chunks = previous.chunks + chunks.size,
                gatedDetections = previous.gatedDetections + gated,
                lastChunkNanos = elapsed / chunks.size,
                maxChunkNanos = maxOf(previous.maxChunkNanos, elapsed / chunks.size),
            )
        return detected
    }

    private fun drain() {
        try {
            while (running) {
                val frame = queue.take(POLL_MS) ?: continue
                synchronized(this) {
                    if (running) process(frame).forEach(mutableEvents::tryEmit)
                }
            }
        } catch (error: Exception) {
            running = false
            mutableStatus.value = SpotterStatus.Unavailable("keyword spotter failed: ${error.message ?: error::class.simpleName}")
        }
    }

    private companion object {
        const val SAMPLE_NANOS = 1_000_000_000L / OpenWakeWordPipeline.SAMPLE_RATE
        const val POLL_MS = 100L
    }
}
