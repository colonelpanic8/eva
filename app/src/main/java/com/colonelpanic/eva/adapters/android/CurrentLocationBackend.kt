package com.colonelpanic.eva.adapters.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToLong

/**
 * Reads where the phone is from Android's own location service, with the nearest street address
 * when the platform geocoder has one.
 */
class CurrentLocationBackend(
    private val context: Context,
    private val geocoder: Geocoder = Geocoder(context),
) : ExecutionBackend {
    private val manager get() = context.getSystemService(LocationManager::class.java)

    override suspend fun unavailableReason(): String? =
        when {
            !isGranted(context) -> PERMISSION_HINT
            manager?.let(LocationManagerCompat::isLocationEnabled) != true -> "Location is turned off on this phone. $NOTHING"
            else -> null
        }

    override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
        val manager = manager ?: return ExecutionOutcome(InvocationStatus.NOT_EXECUTED, "This phone has no location service. $NOTHING")
        val precise = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val location =
            fresh(manager, precise) ?: lastKnown(manager)
                ?: return ExecutionOutcome(
                    InvocationStatus.FAILED,
                    "The phone did not report a location. Android withholds it while EVA is in the background " +
                        "outside a voice session. $NOTHING",
                )
        val address = address(location)
        val ageSeconds = ((SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000_000L).coerceAtLeast(0)
        val accuracy = location.accuracy.takeIf { location.hasAccuracy() }?.roundToLong()
        val text =
            buildString {
                append("The phone is at ")
                append(String.format(Locale.ROOT, "%.6f, %.6f", location.latitude, location.longitude))
                accuracy?.let { append(", within about $it m") }
                append(", measured ${age(ageSeconds)}.")
                if (!precise) append(" EVA has approximate location only, so this may be off by a few kilometers.")
                address?.let { append(" Nearest address: $it.") }
            }
        val data =
            buildJsonObject {
                put("latitude", location.latitude)
                put("longitude", location.longitude)
                accuracy?.let { put("accuracyMeters", it) }
                put("ageSeconds", ageSeconds)
                put("precise", precise)
                address?.let { put("address", it) }
            }
        return ExecutionOutcome(InvocationStatus.COMPLETED, text, data)
    }

    @SuppressLint("MissingPermission")
    private suspend fun fresh(
        manager: LocationManager,
        precise: Boolean,
    ): Location? {
        val enabled = manager.getProviders(true)
        val provider =
            listOfNotNull(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) LocationManager.FUSED_PROVIDER else null,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.GPS_PROVIDER.takeIf { precise },
            ).firstOrNull { it in enabled } ?: return null
        return withTimeoutOrNull(FIX_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                try {
                    LocationManagerCompat.getCurrentLocation(manager, provider, signal, ContextCompat.getMainExecutor(context)) {
                        continuation.resume(it)
                    }
                } catch (_: SecurityException) {
                    continuation.resume(null)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(manager: LocationManager): Location? =
        manager
            .getProviders(true)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.elapsedRealtimeNanos }

    private suspend fun address(location: Location): String? {
        if (!Geocoder.isPresent()) return null
        val found =
            withTimeoutOrNull(ADDRESS_MILLIS) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    suspendCancellableCoroutine { continuation ->
                        geocoder.getFromLocation(
                            location.latitude,
                            location.longitude,
                            1,
                            object : Geocoder.GeocodeListener {
                                override fun onGeocode(addresses: MutableList<Address>) = continuation.resume(addresses.firstOrNull())

                                override fun onError(errorMessage: String?) = continuation.resume(null)
                            },
                        )
                    }
                } else {
                    runInterruptible(Dispatchers.IO) {
                        @Suppress("DEPRECATION")
                        runCatching { geocoder.getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull() }.getOrNull()
                    }
                }
            } ?: return null
        return (0..found.maxAddressLineIndex).mapNotNull(found::getAddressLine).joinToString(", ").ifBlank { null }
    }

    private fun age(seconds: Long) =
        when {
            seconds < 60 -> "just now"
            seconds < 3_600 -> "${seconds / 60} min ago"
            else -> "${seconds / 3_600} h ago"
        }

    companion object {
        private const val FIX_MILLIS = 8_000L
        private const val ADDRESS_MILLIS = 4_000L
        private const val NOTHING = "No location was shared."
        const val PERMISSION_HINT = "EVA does not have the Location permission; the user can grant it in EVA's app settings. $NOTHING"

        fun isGranted(context: Context): Boolean =
            granted(context, Manifest.permission.ACCESS_COARSE_LOCATION) || granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

        private fun granted(
            context: Context,
            permission: String,
        ) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}
