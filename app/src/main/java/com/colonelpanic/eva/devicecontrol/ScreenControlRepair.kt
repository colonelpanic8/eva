package com.colonelpanic.eva.devicecontrol

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.colonelpanic.eva.adapters.android.DeviceControlHost
import com.colonelpanic.eva.devicecontrol.portal.PortalHealth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** What tapping a backend that is not ready does: fix it in place where EVA can, else open where the user can. */
sealed interface RepairOutcome {
    val message: String

    /** A real screen read through the backend worked afterwards. */
    data class Fixed(
        override val message: String,
    ) : RepairOutcome

    data class Failed(
        override val message: String,
    ) : RepairOutcome

    /** The fix needs the user in another app or a system screen. */
    data class Open(
        val intent: Intent,
        override val message: String,
    ) : RepairOutcome

    /** The fix is in EVA's Screen control settings. */
    data class OpenSettings(
        override val message: String,
    ) : RepairOutcome
}

/** The Shizuku operations a repair uses; [DeviceControlHost] in the app. */
interface ShizukuHelper {
    suspend fun accessStatus(): String

    suspend fun requestAccess(): String

    suspend fun restartHelper()

    suspend fun enableAccessibilityService(
        component: ComponentName,
        restart: Boolean,
    )
}

data class PortalService(
    val component: ComponentName,
    val enabled: Boolean,
)

class ScreenControlRepair(
    private val context: Context,
    private val helper: () -> ShizukuHelper?,
    /** Null when no Portal token is saved. */
    private val portalHealth: suspend () -> PortalHealth?,
    private val portalPort: () -> Int,
    /** Reads the screen through the backend; null once it answered, else why not. */
    private val verify: suspend (backend: String) -> String?,
    private val taskRunning: () -> Boolean,
    private val portalService: () -> PortalService? = { installedPortalService(context) },
    private val portalStartMillis: Long = PORTAL_START_MILLIS,
    private val elapsedMillis: () -> Long = android.os.SystemClock::elapsedRealtime,
) {
    private val restoreLock = Mutex()
    private var lastRestoreMillis: Long? = null

    suspend fun repair(backend: String): RepairOutcome = if (backend == "portal") repairPortal() else repairShizuku()

    /**
     * Puts Portal's accessibility service back when it is no longer listed, without a tap.
     * Android drops a force-stopped app's services from that list, and Play Store force-stops
     * sideloaded Portal every few days. A service that is listed but silent is left to a tap.
     * Returns whether Portal answers afterwards.
     */
    suspend fun restorePortal(): Boolean = restoreLock.withLock { restorePortalOnce() }

    private suspend fun restorePortalOnce(): Boolean {
        val service = portalService()?.takeUnless { it.enabled } ?: return false
        val last = lastRestoreMillis
        if (last != null && elapsedMillis() - last < RESTORE_INTERVAL_MILLIS) return false
        val shizuku = helper()?.takeIf { it.accessStatus() == DeviceControlHost.ALLOWED } ?: return false
        lastRestoreMillis = elapsedMillis()
        try {
            shizuku.enableAccessibilityService(service.component, restart = false)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return false
        }
        return awaitPortal()
    }

    private suspend fun awaitPortal(): Boolean =
        withTimeoutOrNull(portalStartMillis) {
            while (portalHealth() != PortalHealth.READY) delay(POLL_MILLIS)
        } != null

    private suspend fun repairPortal(): RepairOutcome {
        when (portalHealth()) {
            null -> return RepairOutcome.OpenSettings("Save Portal's bearer token in Screen control settings.")
            PortalHealth.UNAUTHORIZED -> return RepairOutcome.OpenSettings("Portal rejected EVA's token. Save Portal's current token.")
            PortalHealth.READY -> return confirm("portal", "Portal is answering")
            PortalHealth.UNREACHABLE -> Unit
        }
        val service =
            portalService()
                ?: return RepairOutcome.Failed("Portal is not installed, so EVA cannot start it. Install Portal or use Shizuku.")
        val settings = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        val shizuku = helper()?.takeIf { it.accessStatus() == DeviceControlHost.ALLOWED }
        if (shizuku == null) {
            return RepairOutcome.Open(
                settings,
                "Turn on Portal's accessibility service, then come back to EVA. With Shizuku running and EVA allowed, EVA does this itself.",
            )
        }
        try {
            shizuku.enableAccessibilityService(service.component, restart = service.enabled)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return RepairOutcome.Open(
                settings,
                "EVA could not turn on Portal's accessibility service through Shizuku: ${reason(error)}. Turn it on here, then come back.",
            )
        }
        val did = if (service.enabled) "Restarted Portal's accessibility service" else "Turned Portal's accessibility service back on"
        if (!awaitPortal()) {
            val portal = context.packageManager.getLaunchIntentForPackage(service.component.packageName)
            return RepairOutcome.Open(
                portal ?: settings,
                "$did, but Portal is still not answering on port ${portalPort()}. Check that Portal's local server is on.",
            )
        }
        return confirm("portal", did)
    }

    private suspend fun repairShizuku(): RepairOutcome {
        val shizuku = helper() ?: return RepairOutcome.Failed("Screen control through Shizuku requires Android 11 or newer.")
        return when (val status = shizuku.accessStatus()) {
            DeviceControlHost.ALLOWED -> {
                if (taskRunning()) {
                    return RepairOutcome.Failed("A device task is using the screen. Stop it before restarting EVA's Shizuku helper.")
                }
                try {
                    shizuku.restartHelper()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    return RepairOutcome.Failed("EVA's Shizuku helper did not restart: ${reason(error)}")
                }
                confirm("shizuku", "Restarted EVA's Shizuku helper")
            }

            DeviceControlHost.NOT_ALLOWED -> {
                val granted = shizuku.requestAccess()
                if (granted == DeviceControlHost.ALLOWED) confirm("shizuku", "Shizuku allowed EVA") else RepairOutcome.Failed(granted)
            }

            DeviceControlHost.SERVER_STOPPED -> {
                // Shizuku sends its binder when EVA comes back to the front, so a round trip also
                // recovers a server that restarted while EVA stayed open.
                context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)?.let {
                    RepairOutcome.Open(it, "Start Shizuku if it is stopped, then come back to EVA. EVA reconnects when you return.")
                } ?: RepairOutcome.Failed(status)
            }

            else -> {
                RepairOutcome.Failed(status)
            }
        }
    }

    private suspend fun confirm(
        backend: String,
        did: String,
    ): RepairOutcome {
        val problem = verify(backend)
        return if (problem == null) RepairOutcome.Fixed("$did. It read the screen.") else RepairOutcome.Failed("$did, but $problem")
    }

    private fun reason(error: Exception) = error.message ?: error.javaClass.simpleName

    companion object {
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private val PORTAL_PACKAGES = setOf("com.mobilerun.portal", "com.droidrun.portal")
        private const val PORTAL_START_MILLIS = 8_000L
        private const val POLL_MILLIS = 400L
        private const val RESTORE_INTERVAL_MILLIS = 60_000L

        fun installedPortalService(context: Context): PortalService? {
            val accessibility = context.getSystemService(AccessibilityManager::class.java) ?: return null
            val component =
                accessibility.installedAccessibilityServiceList
                    .map { ComponentName(it.resolveInfo.serviceInfo.packageName, it.resolveInfo.serviceInfo.name) }
                    .firstOrNull { it.packageName in PORTAL_PACKAGES } ?: return null
            val enabled =
                Settings.Secure
                    .getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                    .orEmpty()
                    .split(':')
                    .any { ComponentName.unflattenFromString(it) == component }
            return PortalService(component, enabled)
        }
    }
}
