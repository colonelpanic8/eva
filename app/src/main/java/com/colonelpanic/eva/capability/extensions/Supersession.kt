package com.colonelpanic.eva.capability.extensions

/** A declarative package folded under the app extension that speaks for its target app. */
data class Supersession(
    val owner: String,
    val actions: Set<String>,
)

/**
 * An app's own extension speaks for that app: a declarative package targeting it keeps only the
 * actions the app does not offer itself. Keyed by the package's instance ID.
 */
fun supersessions(installed: List<InstalledExtension>): Map<String, Supersession> {
    val apps =
        installed
            .filter { it.identity is ExtensionIdentity && it.descriptor != null }
            .associateBy { it.packageName }
    return installed
        .filter { it.identity is PackageIdentity }
        .mapNotNull { entry ->
            val owners = entry.androidPackages.mapNotNull(apps::get).ifEmpty { return@mapNotNull null }
            checkNotNull(entry.identity).instanceId to
                Supersession(
                    checkNotNull(owners.first().identity).instanceId,
                    owners.flatMap { owner -> checkNotNull(owner.descriptor).capabilities.map { it.name } }.toSet(),
                )
        }.toMap()
}
