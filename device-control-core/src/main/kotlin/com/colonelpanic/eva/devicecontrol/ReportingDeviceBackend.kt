package com.colonelpanic.eva.devicecontrol

import com.colonelpanic.eva.devicecontrol.proto.Action
import com.colonelpanic.eva.devicecontrol.proto.ActionResult
import com.colonelpanic.eva.devicecontrol.proto.BackendUnavailable
import com.colonelpanic.eva.devicecontrol.proto.ErrorInfo
import com.colonelpanic.eva.devicecontrol.proto.Observation
import com.colonelpanic.eva.devicecontrol.proto.Timeout
import kotlinx.coroutines.CancellationException

/**
 * Reports what real screen reads and inputs say about a backend's health: null after it
 * answered, a reason after it failed. A protected or changed screen is the screen's state, not
 * the backend's, so it reports nothing.
 */
class ReportingDeviceBackend(
    private val label: String,
    private val delegate: DeviceBackend,
    private val report: (failure: String?) -> Unit,
) : DeviceBackend {
    override suspend fun observe(): Observation =
        try {
            delegate.observe().also { report(null) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: ObservationFailure) {
            backendFault(error.error)?.let { report("$label couldn't read the screen: $it") }
            throw error
        } catch (error: Exception) {
            report("$label couldn't read the screen: ${error.message ?: error.javaClass.simpleName}")
            throw error
        }

    override suspend fun perform(action: Action): ActionResult {
        val result =
            try {
                delegate.perform(action)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                report("$label couldn't deliver the input: ${error.message ?: error.javaClass.simpleName}")
                throw error
            }
        report(backendFault(result.error)?.let { "$label couldn't deliver the input: $it" })
        return result
    }

    private fun backendFault(error: ErrorInfo?): String? =
        when (error) {
            is BackendUnavailable -> "the ${error.backend} backend is unavailable"
            is Timeout -> "it did not respond within ${error.timeoutS} seconds"
            else -> null
        }
}
