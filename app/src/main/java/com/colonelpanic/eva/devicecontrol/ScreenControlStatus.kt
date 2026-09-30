package com.colonelpanic.eva.devicecontrol

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Readiness of each route EVA uses to read and act on the screen, in device-task preference order. */
data class ScreenControlStatus(
    val routes: List<Route> = emptyList(),
) {
    data class Route(
        val name: String,
        val problem: String?,
    )

    val enabled get() = routes.isNotEmpty()

    /** The route a new device task would use. */
    val preferred get() = routes.indexOfFirst { it.problem == null }.takeIf { it >= 0 }
}

/** Direct screen tools always use Shizuku, so it is shown even when device tasks leave it out. */
class ScreenControlMonitor(
    private val enabled: () -> Boolean,
    private val backends: () -> List<String>,
    private val problem: suspend (String) -> String?,
    private val label: (String) -> String,
) {
    private val mutableStatus = MutableStateFlow(ScreenControlStatus())
    val status = mutableStatus.asStateFlow()

    suspend fun refresh(): ScreenControlStatus {
        val status =
            if (!enabled()) {
                ScreenControlStatus()
            } else {
                ScreenControlStatus((backends() + "shizuku").distinct().map { ScreenControlStatus.Route(label(it), problem(it)) })
            }
        mutableStatus.value = status
        return status
    }
}
