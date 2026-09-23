package com.colonelpanic.eva.adapters.declarative

import com.colonelpanic.eva.capability.extensions.PackageIdentity
import java.util.UUID

/**
 * A catalog package that EVA installs and approves once per configuration. The shipped copy is
 * byte-identical to the catalog file at the same path, so a later catalog refresh updates the
 * same installation.
 */
data class DefaultPackage(
    val id: String,
    /** Path inside both the app assets and the catalog, `packages/<name>.json`. */
    val path: String,
    /** A content authority that must resolve before the default is installed; null installs it everywhere. */
    val requiresProvider: String? = null,
) {
    val source: String get() = PluginRepository.installationSource(DEFAULT_PLUGIN_REPOSITORY)
    val identity: PackageIdentity get() = PackageIdentity(UUID.nameUUIDFromBytes("eva.default:$id".toByteArray()).toString())
}

object DefaultPackages {
    val all =
        listOf(
            DefaultPackage("android.google-maps", "packages/google-maps.json"),
            DefaultPackage("android.web", "packages/web.json"),
            DefaultPackage("android.email", "packages/email.json"),
            DefaultPackage("android.calendar", "packages/calendar.json"),
            DefaultPackage("android.settings", "packages/settings.json"),
            DefaultPackage("android.clock", "packages/clock.json"),
            DefaultPackage("android.paseo", "packages/paseo.json", requiresProvider = "sh.paseo.assistant"),
        )
}
