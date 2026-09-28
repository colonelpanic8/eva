package com.colonelpanic.eva.messaging

import com.colonelpanic.eva.adapters.declarative.PackageMessagingService
import com.colonelpanic.eva.capability.ExecutionBackend

/**
 * Messaging services that installed extension packages provide. Names come from the user's package
 * settings; the backends are the granted, availability-checked package capabilities, so disabling an
 * extension or declining its send action removes the service's reach without touching the shared tools.
 */
interface MessagingServices {
    fun all(): List<PackageMessagingService>

    /** The granted backend for a package capability, or null when the extension or action is not allowed. */
    fun backend(capabilityId: String): ExecutionBackend?

    /** The service the model named: its service name or its label, exactly, ignoring case. */
    fun find(service: String): PackageMessagingService? =
        all().firstOrNull { service.equals(it.service, ignoreCase = true) || service.equals(it.label, ignoreCase = true) }

    companion object {
        val None =
            object : MessagingServices {
                override fun all(): List<PackageMessagingService> = emptyList()

                override fun backend(capabilityId: String): ExecutionBackend? = null
            }
    }
}
