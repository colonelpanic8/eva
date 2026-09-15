package com.colonelpanic.eva.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.colonelpanic.eva.adapters.declarative.BasicCredential
import com.colonelpanic.eva.adapters.declarative.InstalledPlugin
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
import com.colonelpanic.eva.data.configuration.HttpServiceBinding
import com.colonelpanic.eva.data.configuration.PortablePackage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class PackageConfigurationEntry(
    val id: String,
    val title: String,
    val origin: String?,
    val credentialName: String?,
    val waitMillis: Long?,
    val credentialAvailable: Boolean = true,
)

data class PortablePackageSettings(
    val repository: String,
    val bundledInstances: Map<String, String>,
    val installed: List<PortablePackage>,
    val waitMillis: Map<String, Long>,
    val services: List<HttpServiceBinding>,
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
    private val onChanged: () -> Unit = {},
    private val onCredentialChanged: (String) -> Unit = {},
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

    private val bundledDefinitions: Map<String, PackageDefinition> =
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
                    name to source
                }
            }.toMap()
    private val imports =
        loadOrReject("Repository plugins") {
            com.colonelpanic.eva.adapters.declarative.PluginInstallations(
                { prefs.getString("imports", null) },
                { encoded -> savePreferences { putString("imports", encoded) } },
            )
        }
    private val bundledSources: Map<PackageIdentity, PackageDefinition>
        get() = bundledDefinitions.mapKeys { (name, _) -> PackageIdentity(requireNotNull(prefs.getString("instance:$name", null))) }
    private val sources: Map<PackageIdentity, PackageDefinition>
        get() = bundledSources + imported().associate { it.identity to it.definition }

    fun imported() = imports?.all().orEmpty()

    val repositorySource: String get() =
        prefs.getString("repository", null)
            ?: com.colonelpanic.eva.adapters.declarative.DEFAULT_PLUGIN_INDEX

    fun saveRepository(source: String) {
        savePreferences { putString("repository", source) }
        onChanged()
    }

    fun installPlugin(preview: com.colonelpanic.eva.adapters.declarative.PluginPreview) {
        checkNotNull(imports) { "Plugin storage could not be loaded" }.install(preview)
        mutable.value = entries()
        onChanged()
    }

    fun removePlugin(instance: String) {
        checkNotNull(imports).remove(instance)
        secrets.clear("package:$instance:basic")
        savePreferences {
            remove("origin:$instance")
            remove("wait:$instance")
        }
        mutable.value = entries()
        onChanged()
    }

    private val mutable = MutableStateFlow(entries())
    val state = mutable.asStateFlow()
    private val defaults = MutableStateFlow(modeDefaults())
    val waits = defaults.asStateFlow()

    private fun storedCredential(identity: PackageIdentity): BasicCredential? =
        secrets.read("package:${identity.id}:basic")?.let { runCatching { BasicCredential.decode(it) }.getOrNull() }

    private fun desiredOrigin(identity: PackageIdentity): String? =
        prefs.getString("origin:${identity.id}", null) ?: storedCredential(identity)?.origin

    private fun credential(identity: PackageIdentity): BasicCredential? =
        storedCredential(identity)?.takeIf { it.origin == desiredOrigin(identity) }

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
            savePreferences { putString("origin:$id", saved.origin) }
            mutable.value = entries()
            onCredentialChanged(
                com.colonelpanic.eva.data.configuration.EvaConfigurationCodec
                    .packageSecretId(id),
            )
            onChanged()
        }.exceptionOrNull()?.let { it.message ?: "Could not save credentials." }

    fun clear(id: String) {
        require(sources.keys.any { it.id == id })
        secrets.clear("package:$id:basic")
        savePreferences { remove("origin:$id") }
        mutable.value = entries()
        onCredentialChanged(
            com.colonelpanic.eva.data.configuration.EvaConfigurationCodec
                .packageSecretId(id),
        )
        onChanged()
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
            onChanged()
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
                desiredOrigin(identity),
                source
                    .httpBindings()
                    .mapNotNull { it.credential }
                    .distinct()
                    .singleOrNull(),
                override(identity.id),
                credential(identity) != null,
            )
        }

    fun portable(): PortablePackageSettings =
        PortablePackageSettings(
            repositorySource,
            prefs.all
                .mapNotNull { (key, value) ->
                    if (key.startsWith("instance:") && value is String) key.removePrefix("instance:") to value else null
                }.toMap(),
            imported().map { PortablePackage(it.identity.id, it.source, it.url, it.json) },
            prefs.all
                .mapNotNull { (key, value) ->
                    if (key.startsWith("wait:") && value is Long) key.removePrefix("wait:") to value else null
                }.toMap(),
            prefs.all
                .mapNotNull { (key, value) ->
                    if (key.startsWith("origin:") && value is String) {
                        val instance = key.removePrefix("origin:")
                        HttpServiceBinding(
                            instance,
                            value,
                            com.colonelpanic.eva.data.configuration.EvaConfigurationCodec
                                .packageSecretId(instance),
                        )
                    } else {
                        null
                    }
                },
        )

    fun missingCredentials(services: List<HttpServiceBinding>): List<String> =
        services.mapNotNull { service ->
            val identity = PackageIdentity(service.packageInstance)
            if (storedCredential(identity)?.origin == service.origin) null else service.credential
        }

    fun validateRestore(restored: PortablePackageSettings): List<InstalledPlugin> =
        restored.installed
            .map { item ->
                val definition = PackageCodec.decode(item.document)
                InstalledPlugin(PackageIdentity(item.instance), item.source, item.url, definition, item.document)
            }.also(com.colonelpanic.eva.adapters.declarative.PluginInstallations::validate)

    fun restore(restored: PortablePackageSettings): List<String> {
        val installed =
            validateRestore(restored)
        checkNotNull(imports) { "Plugin storage could not be loaded" }.restore(installed)
        savePreferences {
            putString("repository", restored.repository)
            prefs.all.keys
                .filter { key ->
                    key.startsWith("instance:") &&
                        key.removePrefix("instance:") !in bundledDefinitions &&
                        key.removePrefix("instance:") !in restored.bundledInstances
                }.forEach(::remove)
            restored.bundledInstances.forEach { (name, instance) ->
                putString("instance:$name", instance)
            }
            prefs.all.keys
                .filter { it.startsWith("wait:") || it.startsWith("origin:") }
                .forEach(::remove)
            restored.waitMillis.forEach { (id, value) -> putLong("wait:$id", value) }
            restored.services.forEach { service -> putString("origin:${service.packageInstance}", service.origin) }
        }
        defaults.value = modeDefaults()
        mutable.value = entries()
        return buildList {
            (restored.bundledInstances.keys - bundledDefinitions.keys).sorted().forEach {
                add(
                    "Bundled package $it is not in this EVA build.",
                )
            }
            (bundledDefinitions.keys - restored.bundledInstances.keys).sorted().forEach {
                add(
                    "Bundled package $it is new on this EVA build.",
                )
            }
        }
    }

    // KTX edit discards the commit result; installation identity must fail closed on write failure.
    @SuppressLint("UseKtx")
    private fun savePreferences(change: SharedPreferences.Editor.() -> Unit) {
        check(prefs.edit().apply(change).commit()) { "Could not save package settings." }
    }
}
