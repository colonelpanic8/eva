package com.colonelpanic.eva.devicecontrol

import com.colonelpanic.eva.devicecontrol.proto.Action
import com.colonelpanic.eva.devicecontrol.proto.ActionResult
import com.colonelpanic.eva.devicecontrol.proto.Observation
import kotlinx.coroutines.CancellationException

/**
 * Uses backends in preference order. Until the first action, one that is not ready or cannot read
 * the screen hands over to the next; after it the task stays on that backend, so no mutation is
 * ever repeated elsewhere.
 */
class PreferredDeviceBackend(
    private val candidates: List<Candidate>,
) : DeviceBackend {
    class Candidate(
        val name: String,
        val problem: suspend () -> String?,
        val open: () -> DeviceBackend,
    )

    private var index = 0
    private var current: DeviceBackend? = null
    private var pinned = false
    private val failures = mutableListOf<String>()

    /** The backend serving the task, once one has read the screen. */
    var active: String? = null
        private set

    init {
        require(candidates.isNotEmpty())
    }

    override suspend fun observe(): Observation {
        current?.takeIf { pinned }?.let { return it.observe() }
        while (index < candidates.size) {
            val candidate = candidates[index]
            val backend =
                current ?: run {
                    val problem = candidate.problem()
                    if (problem != null) {
                        failures += "${candidate.name}: $problem"
                        index++
                        return@run null
                    }
                    candidate.open().also { current = it }
                } ?: continue
            try {
                return backend.observe().also { active = candidate.name }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failures += "${candidate.name}: ${error.message ?: error.javaClass.simpleName}"
                current = null
                active = null
                index++
            }
        }
        throw IllegalStateException("No screen backend is available. ${failures.joinToString("; ")}")
    }

    override suspend fun perform(action: Action): ActionResult {
        val backend = checkNotNull(current) { "The screen has not been read yet" }
        pinned = true
        return backend.perform(action)
    }
}
