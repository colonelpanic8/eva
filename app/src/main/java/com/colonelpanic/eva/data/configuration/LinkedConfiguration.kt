package com.colonelpanic.eva.data.configuration

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ConfigurationApplyResult(
    val setupRequired: List<String> = emptyList(),
)

sealed interface LinkedConfigurationResult {
    data class Loaded(
        val setupRequired: List<String>,
    ) : LinkedConfigurationResult

    data class Saved(
        val setupRequired: List<String>,
    ) : LinkedConfigurationResult

    data class Conflict(
        val setupRequired: List<String>,
    ) : LinkedConfigurationResult
}

class LinkedConfiguration(
    private val snapshot: suspend () -> EvaConfiguration,
    private val apply: suspend (EvaConfiguration) -> ConfigurationApplyResult,
    private val reassess: suspend (EvaConfiguration, List<String>) -> ConfigurationApplyResult = { _, previous ->
        ConfigurationApplyResult(previous)
    },
) {
    private val updates = Mutex()
    private var directory: ConfigurationDirectory? = null
    private var resolved: ResolvedConfiguration? = null
    private var setupRequired = emptyList<String>()

    suspend fun attach(selected: ConfigurationDirectory): LinkedConfigurationResult =
        updates.withLock {
            val existing = selected.read(EvaConfigurationCodec.FILE_NAME)
            if (existing == null) {
                val current = snapshot()
                val text = EvaConfigurationCodec.encode(EvaConfigurationCodec.complete(current))
                selected.replaceRoot(text, expectedRootFingerprint = null)
                val saved = EvaConfigurationCodec.resolve(reader = selected)
                require(saved.configuration == current) { "Saved configuration did not resolve to the current settings." }
                directory = selected
                resolved = saved
                LinkedConfigurationResult.Saved(setupRequired)
            } else {
                val disk = EvaConfigurationCodec.resolve(reader = selected)
                val outcome = applyTransactionally(disk.configuration)
                directory = selected
                resolved = disk
                setupRequired = outcome.setupRequired
                LinkedConfigurationResult.Loaded(outcome.setupRequired)
            }
        }

    suspend fun reload(force: Boolean = false): LinkedConfigurationResult =
        updates.withLock {
            val selected = requireNotNull(directory) { "No configuration folder is linked." }
            load(selected, force)
        }

    suspend fun localChange(): LinkedConfigurationResult =
        updates.withLock {
            val selected = directory ?: return@withLock LinkedConfigurationResult.Saved(setupRequired)
            val disk = EvaConfigurationCodec.resolve(reader = selected)
            val previous = resolved
            if (previous == null || disk.fingerprint != previous.fingerprint) {
                val outcome = applyTransactionally(disk.configuration)
                resolved = disk
                setupRequired = outcome.setupRequired
                return@withLock LinkedConfigurationResult.Conflict(outcome.setupRequired)
            }
            val current = snapshot()
            val document = EvaConfigurationCodec.overrides(current, disk.included, disk.root.include)
            val text = EvaConfigurationCodec.encode(document)
            selected.replaceRoot(text, disk.rootFingerprint)
            val saved = EvaConfigurationCodec.resolve(reader = selected)
            resolved = saved
            if (saved.configuration == current) {
                LinkedConfigurationResult.Saved(setupRequired)
            } else {
                val outcome = applyTransactionally(saved.configuration)
                setupRequired = outcome.setupRequired
                LinkedConfigurationResult.Conflict(outcome.setupRequired)
            }
        }

    fun linkedLabel(): String? = directory?.label

    private suspend fun load(
        selected: ConfigurationDirectory,
        force: Boolean,
    ): LinkedConfigurationResult {
        val disk = EvaConfigurationCodec.resolve(reader = selected)
        if (!force && disk.fingerprint == resolved?.fingerprint) {
            val outcome = reassess(disk.configuration, setupRequired)
            setupRequired = outcome.setupRequired
            return LinkedConfigurationResult.Loaded(outcome.setupRequired)
        }
        val outcome = applyTransactionally(disk.configuration)
        resolved = disk
        setupRequired = outcome.setupRequired
        return LinkedConfigurationResult.Loaded(outcome.setupRequired)
    }

    private suspend fun applyTransactionally(configuration: EvaConfiguration): ConfigurationApplyResult = apply(configuration)
}
