package com.colonelpanic.eva.devicecontrol

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Health of each route EVA uses to read and act on the screen, in device-task preference order. */
data class ScreenControlStatus(
    val routes: List<Route> = emptyList(),
) {
    enum class Health {
        /** Set up, and nothing has failed since it last worked. */
        READY,

        /** Not set up yet; the problem says how to fix it. */
        SETUP_NEEDED,

        /** Its check failed after it last worked; screen control skips it. */
        DEGRADED,

        /** The last real screen read or input through it failed. */
        UNHEALTHY,
    }

    data class Route(
        val name: String,
        val problem: String?,
        val health: Health = if (problem == null) Health.READY else Health.UNHEALTHY,
        /** Wall-clock time of the evidence behind [problem]: the failure, or when the check started failing. */
        val sinceMillis: Long? = null,
    )

    val enabled get() = routes.isNotEmpty()

    /** The route a new device task would try first: checks skip only routes that are not set up or fail them. */
    val preferred get() = routes.indexOfFirst { it.health == Health.READY || it.health == Health.UNHEALTHY }.takeIf { it >= 0 }
}

/**
 * Assumes a set-up backend works and lets real screen reads and inputs say otherwise. A failure
 * marks the route unhealthy until the next success through it; a failing check only marks it
 * degraded when nothing has worked through it since the check started failing, and a passing
 * check never clears a real failure. Device tasks and direct screen tools share the backend order.
 */
class ScreenControlMonitor(
    private val enabled: () -> Boolean,
    private val backends: () -> List<String>,
    /** Why a backend is not set up (not installed, not allowed, no token), or null. */
    private val setup: suspend (String) -> String?,
    /** A cheap reachability check; secondary evidence only. */
    private val probe: suspend (String) -> String?,
    private val label: (String) -> String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Evidence {
        var setup: String? = null
        var probeFailure: String? = null
        var probeFailingSince: Long? = null
        var failure: String? = null
        var failedAt: Long? = null
        var lastSuccess: Long? = null
    }

    private val evidence = mutableMapOf<String, Evidence>()
    private val mutableStatus = MutableStateFlow(ScreenControlStatus())
    val status = mutableStatus.asStateFlow()

    /** Records a real screen read or input through [backend]: null when it answered, else why it failed. */
    fun record(
        backend: String,
        failure: String?,
    ) {
        synchronized(evidence) {
            val entry = evidence.getOrPut(backend, ::Evidence)
            entry.failure = failure
            if (failure == null) entry.lastSuccess = now() else entry.failedAt = now()
        }
        publish()
    }

    suspend fun refresh(): ScreenControlStatus {
        if (enabled()) {
            for (backend in backends().distinct()) {
                val setupProblem = setup(backend)
                val probeProblem = if (setupProblem == null) probe(backend) else null
                synchronized(evidence) {
                    val entry = evidence.getOrPut(backend, ::Evidence)
                    entry.setup = setupProblem
                    if (probeProblem == null) {
                        entry.probeFailingSince = null
                    } else if (entry.probeFailure == null) {
                        entry.probeFailingSince = now()
                    }
                    entry.probeFailure = probeProblem
                }
            }
        }
        return publish()
    }

    private fun publish(): ScreenControlStatus {
        val status =
            if (!enabled()) {
                ScreenControlStatus()
            } else {
                synchronized(evidence) {
                    ScreenControlStatus(backends().distinct().map { route(it, evidence[it] ?: Evidence()) })
                }
            }
        mutableStatus.value = status
        return status
    }

    private fun route(
        backend: String,
        entry: Evidence,
    ): ScreenControlStatus.Route {
        val name = label(backend)
        val since = entry.probeFailingSince
        val lastSuccess = entry.lastSuccess
        return when {
            entry.setup != null -> {
                ScreenControlStatus.Route(name, entry.setup, ScreenControlStatus.Health.SETUP_NEEDED)
            }

            entry.failure != null -> {
                ScreenControlStatus.Route(name, entry.failure, ScreenControlStatus.Health.UNHEALTHY, entry.failedAt)
            }

            entry.probeFailure != null && (lastSuccess == null || since == null || since > lastSuccess) -> {
                ScreenControlStatus.Route(name, entry.probeFailure, ScreenControlStatus.Health.DEGRADED, since)
            }

            else -> {
                ScreenControlStatus.Route(name, null, ScreenControlStatus.Health.READY)
            }
        }
    }
}
