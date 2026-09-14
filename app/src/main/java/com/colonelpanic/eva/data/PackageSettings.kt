package com.colonelpanic.eva.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.colonelpanic.eva.adapters.declarative.BasicCredential
import com.colonelpanic.eva.adapters.declarative.LoadedPackage
import com.colonelpanic.eva.adapters.declarative.PackageCodec
import com.colonelpanic.eva.adapters.declarative.PackageDefinition
import com.colonelpanic.eva.adapters.declarative.configurePackage
import com.colonelpanic.eva.adapters.declarative.httpBindings
import com.colonelpanic.eva.capability.InteractionMode
import com.colonelpanic.eva.capability.WaitBudget
import com.colonelpanic.eva.capability.extensions.InstalledExtension
import com.colonelpanic.eva.capability.extensions.PackageIdentity
import com.colonelpanic.eva.capability.extensions.rethrowFatalExtensionFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class PackageConfigurationEntry(
    val id: String,
    val title: String,
    val origin: String?,
    val credentialName: String?,
    val waitMillis: Long?,
)

class PackageSettings(
    context: Context,
    listPackages: () -> List<String> = {
        context.assets
            .list("")
            .orEmpty()
            .toList()
    },
    readPackage: (String) -> String = { name -> context.assets.open(name).use { it.readBytes().toString(Charsets.UTF_8) } },
) {
    private val secrets = SecretStore(context)
    private val prefs = context.getSharedPreferences("eva.packages", Context.MODE_PRIVATE)
    private val rejected = mutableListOf<InstalledExtension>()

    fun unavailable(): List<InstalledExtension> = rejected.toList()

    private fun <T> loadOrReject(
        name: String,
        block: () -> T,
    ): T? =
        try {
            block()
        } catch (failure: Throwable) {
            rethrowFatalExtensionFailure(failure)
            Log.e("EvaExtensions", "Could not load package $name", failure)
            rejected +=
                InstalledExtension(name, null, null, "Package could not be loaded. Update or remove this package, then restart EVA.")
            null
        }

    private val sources: Map<PackageIdentity, PackageDefinition> =
        loadOrReject("Bundled packages", listPackages)
            .orEmpty()
            .filter { it.endsWith(".json") }
            .mapNotNull { name ->
                loadOrReject(name) {
                    val source = PackageCodec.decode(readPackage(name))
                    val identityKey = "instance:$name"
                    val id =
                        prefs.getString(identityKey, null) ?: UUID.randomUUID().toString().also {
                            savePreferences { putString(identityKey, it) }
                        }
                    PackageIdentity(id) to source
                }
            }.toMap()
    private val mutable = MutableStateFlow(entries())
    val state = mutable.asStateFlow()
    private val defaults = MutableStateFlow(modeDefaults())
    val waits = defaults.asStateFlow()

    private fun credential(identity: PackageIdentity): BasicCredential? =
        secrets.read("package:${identity.id}:basic")?.let { runCatching { BasicCredential.decode(it) }.getOrNull() }

    fun credential(
        identity: PackageIdentity,
        origin: String,
        name: String,
    ): BasicCredential? =
        credential(identity)?.takeIf { saved ->
            saved.origin == origin && sources.getValue(identity).httpBindings().any { it.credential == name }
        }

    fun load(): List<LoadedPackage> =
        sources.map { (identity, source) ->
            val saved = credential(identity)
            LoadedPackage(
                identity,
                saved?.let { configurePackage(source, it.origin) } ?: source,
                source.httpBindings().isEmpty() || saved != null,
            )
        }

    fun save(
        id: String,
        url: String,
        username: String,
        password: String,
    ): String? =
        runCatching {
            val identity = sources.keys.single { it.id == id }
            val source = sources.getValue(identity)
            require(
                source
                    .httpBindings()
                    .map { it.origin }
                    .distinct()
                    .size == 1,
            ) { "Configure packages with multiple origins separately." }
            val saved = BasicCredential.create(url, username, password)
            configurePackage(source, saved.origin)
            secrets.write("package:$id:basic", saved.encode())
            mutable.value = entries()
        }.exceptionOrNull()?.let { it.message ?: "Could not save credentials." }

    fun clear(id: String) {
        require(sources.keys.any { it.id == id })
        secrets.clear("package:$id:basic")
        mutable.value = entries()
    }

    fun saveWait(
        id: String,
        seconds: String,
    ): String? =
        runCatching {
            require(id in listOf("voice", "typed") || sources.keys.any { it.id == id })
            val value = seconds.trim().takeIf { it.isNotEmpty() }?.toLong()
            require(value == null || value in 1..60) { "Enter 1–60 seconds, or leave blank for the default." }
            savePreferences {
                if (value == null) remove("wait:$id") else putLong("wait:$id", value * 1000)
            }
            defaults.value = modeDefaults()
            mutable.value = entries()
        }.exceptionOrNull()?.let { "Enter 1–60 seconds, or leave blank for the default." }

    fun budget(
        identity: PackageIdentity,
        capabilityMillis: Long?,
        mode: InteractionMode,
    ): WaitBudget = WaitBudget(mode, defaults.value.getValue(mode), capabilityMillis, override(identity.id))

    private fun override(id: String): Long? = prefs.getLong("wait:$id", 0).takeIf { it > 0 }

    private fun modeDefaults(): Map<InteractionMode, Long> =
        InteractionMode.entries.associateWith {
            override(it.name.lowercase()) ?: WaitBudget.defaultMillis(it)
        }

    private fun entries(): List<PackageConfigurationEntry> =
        sources.map { (identity, source) ->
            PackageConfigurationEntry(
                identity.id,
                source.title,
                credential(identity)?.origin,
                source
                    .httpBindings()
                    .mapNotNull { it.credential }
                    .distinct()
                    .singleOrNull(),
                override(identity.id),
            )
        }

    // KTX edit discards the commit result; installation identity must fail closed on write failure.
    @SuppressLint("UseKtx")
    private fun savePreferences(change: SharedPreferences.Editor.() -> Unit) {
        check(prefs.edit().apply(change).commit()) { "Could not save package settings." }
    }
}
