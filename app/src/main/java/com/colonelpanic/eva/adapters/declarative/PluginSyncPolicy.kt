package com.colonelpanic.eva.adapters.declarative

import com.colonelpanic.eva.capability.extensions.PackageIdentity

/**
 * What a refresh of a followed catalog does to one package. A package that is new is installed and,
 * unless the user turned auto-enable off for it, approved outright. A package whose content changed
 * is replaced and keeps actions the user left enabled. Newly declared actions are approved when
 * auto-enable is on; actions the user switched off stay off.
 */
class PluginSyncPolicy(
    private val installed: () -> List<InstalledPlugin>,
    private val autoEnable: (String) -> Boolean,
    private val install: suspend (PluginPreview) -> InstalledPlugin,
    private val adopt: suspend (PackageIdentity) -> Unit,
    private val carryForward: suspend (PackageIdentity, String, Set<String>, Boolean) -> Unit,
) : PluginSync {
    override suspend fun apply(preview: PluginPreview): SyncOutcome {
        val source = PluginRepository.installationSource(preview.source)
        val existing = installed().find { it.source == source && it.definition.id == preview.definition.id }
        if (existing != null && existing.definition.digest == preview.definition.digest) return SyncOutcome.UNCHANGED
        val identity = install(preview).identity
        return if (existing == null) {
            if (autoEnable(preview.definition.id)) adopt(identity)
            SyncOutcome.ADDED
        } else {
            carryForward(
                identity,
                existing.definition.digest,
                existing.definition.capabilities
                    .map { it.name }
                    .toSet(),
                autoEnable(preview.definition.id),
            )
            SyncOutcome.UPDATED
        }
    }
}
