package com.colonelpanic.eva.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import com.colonelpanic.eva.adapters.declarative.BasicCredential
import com.colonelpanic.eva.adapters.declarative.DefaultPackages
import com.colonelpanic.eva.adapters.declarative.InstalledPlugin
import com.colonelpanic.eva.adapters.declarative.LoadedPackage
import com.colonelpanic.eva.adapters.declarative.PackageCodec
import com.colonelpanic.eva.adapters.declarative.PackageDefinition
import com.colonelpanic.eva.adapters.declarative.PluginRepository
import com.colonelpanic.eva.adapters.declarative.configurePackage
import com.colonelpanic.eva.adapters.declarative.contentBindings
import com.colonelpanic.eva.adapters.declarative.httpBindings
import com.colonelpanic.eva.capability.InteractionMode
import com.colonelpanic.eva.capability.WaitBudget
import com.colonelpanic.eva.capability.extensions.PackageIdentity
import com.colonelpanic.eva.data.configuration.HttpServiceBinding
import com.colonelpanic.eva.data.configuration.HttpServiceDefinition
import com.colonelpanic.eva.data.configuration.PackageServiceBinding
import com.colonelpanic.eva.data.configuration.PortablePackage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class PackageConfigurationEntry(
    val id: String,
    val title: String,
    val sourceOrigin: String,
    val serviceName: String?,
    val origin: String?,
    val credentialName: String?,
    val waitMillis: Long?,
    val credentialAvailable: Boolean = true,
    val contentAuthorities: List<String> = emptyList(),
)

data class PortablePackageSettings(
    val repositories: List<String>,
    val installed: List<PortablePackage>,
    val waitMillis: Map<String, Long>,
    /** Version-1 bindings retained only when their package definition is unavailable. */
    val services: List<HttpServiceBinding>,
    val httpServices: Map<String, HttpServiceDefinition> = emptyMap(),
    val serviceBindings: List<PackageServiceBinding> = emptyList(),
    /** Shipped defaults this configuration has already installed once; a later removal stays removed. */
    val appliedDefaults: List<String> = emptyList(),
    /** Extensions whose auto-enable choice differs from the default; absent means a refresh may enable it. */
    val autoEnabled: Map<String, Boolean> = emptyMap(),
)

@Serializable
private data class StoredServiceSettings(
    val http: Map<String, HttpServiceDefinition> = emptyMap(),
    val bindings: List<PackageServiceBinding> = emptyList(),
)

class PackageSettings(
    context: Context,
    private val onChanged: () -> Unit = {},
    private val onCredentialChanged: (String) -> Unit = {},
    private val readAsset: (String) -> String = { name -> context.assets.open(name).use { it.readBytes().toString(Charsets.UTF_8) } },
) {
    private val secrets = SecretStore(context)
    private val prefs = context.getSharedPreferences("eva.packages", Context.MODE_PRIVATE)
    private val imports =
        com.colonelpanic.eva.adapters.declarative.PluginInstallations(
            { prefs.getString("imports", null) },
            { encoded -> savePreferences { putString("imports", encoded) } },
        )
    private val sources
        get() = imported().associate { it.identity to it.definition }

    fun imported() = imports.all()

    /**
     * Every catalog EVA tracks. A configuration written before repositories were a list keeps its
     * single source, so an upgrade changes nothing about which catalog a phone follows.
     */
    val repositorySources: List<String> get() =
        prefs
            .getString(REPOSITORIES, null)
            ?.let { runCatching { Json.decodeFromString<List<String>>(it) }.getOrNull() }
            ?: listOf(
                prefs.getString("repository", null)?.let(PluginRepository::legacySource)
                    ?: com.colonelpanic.eva.adapters.declarative.DEFAULT_PLUGIN_REPOSITORY,
            )

    fun saveRepositories(sources: List<String>) {
        savePreferences { putString(REPOSITORIES, Json.encodeToString(sources.distinct())) }
        mutable.value = entries()
        onChanged()
    }

    /**
     * Whether a catalog refresh may turn this extension's actions on by itself. Enabling is the
     * default for a catalog the user chose to follow. Turning an extension off records the
     * opposite; turning off one action leaves that action off through later refreshes.
     */
    fun autoEnable(packageId: String): Boolean = prefs.getBoolean("auto:$packageId", true)

    fun setAutoEnable(
        packageId: String,
        enabled: Boolean,
    ) {
        savePreferences { putBoolean("auto:$packageId", enabled) }
        mutable.value = entries()
        onChanged()
    }

    fun autoEnabled(): Map<String, Boolean> =
        prefs.all
            .mapNotNull { (key, value) -> if (key.startsWith("auto:") && value is Boolean) key.removePrefix("auto:") to value else null }
            .toMap()

    fun installPlugin(preview: com.colonelpanic.eva.adapters.declarative.PluginPreview): InstalledPlugin {
        val installed = imports.install(preview)
        mutable.value = entries()
        onChanged()
        return installed
    }

    fun removePlugin(instance: String) {
        imports.remove(instance)
        secrets.clear("package:$instance:basic")
        val serviceSettings = serviceSettings()
        val retainedBindings = serviceSettings.bindings.filterNot { it.packageInstance == instance }
        val retainedServices = retainUsedServices(serviceSettings.http, retainedBindings)
        (serviceSettings.http.keys - retainedServices.keys).forEach { secrets.clear(serviceSecretKey(it)) }
        savePreferences {
            remove("origin:$instance")
            remove("wait:$instance")
            putString(SERVICES, Json.encodeToString(StoredServiceSettings(retainedServices, retainedBindings)))
        }
        mutable.value = entries()
        onChanged()
    }

    fun appliedDefaults(): List<String> = prefs.getString(DEFAULTS, null)?.let { Json.decodeFromString<List<String>>(it) }.orEmpty()

    /** Installs each shipped default once per configuration and returns the installations still needing grants. */
    fun adoptDefaults(): List<InstalledPlugin> {
        val applied = appliedDefaults()
        val pending = DefaultPackages.all.filter { it.id !in applied }
        if (pending.isEmpty()) return emptyList()
        val adopted =
            pending.map { default ->
                val json = readAsset(default.path)
                val definition = PackageCodec.decode(json)
                require(definition.id == default.id) { "Shipped default ${default.path} declares ${definition.id}." }
                imports.adopt(InstalledPlugin(default.identity, default.source, default.path, definition, json))
            }
        savePreferences { putString(DEFAULTS, Json.encodeToString(applied + pending.map { it.id })) }
        mutable.value = entries()
        onChanged()
        return adopted
    }

    private val mutable = MutableStateFlow(entries())
    val state = mutable.asStateFlow()
    private val defaults = MutableStateFlow(modeDefaults())
    val waits = defaults.asStateFlow()

    private fun storedCredential(reference: String): BasicCredential? {
        val direct = secrets.read(secretKey(reference))?.let { runCatching { BasicCredential.decode(it) }.getOrNull() }
        if (direct != null || !reference.startsWith("service/package-")) return direct
        val instance = reference.removePrefix("service/package-").removeSuffix("/basic")
        return legacyCredential(PackageIdentity(instance))
    }

    private fun legacyCredential(identity: PackageIdentity): BasicCredential? =
        secrets.read("package:${identity.id}:basic")?.let { runCatching { BasicCredential.decode(it) }.getOrNull() }

    fun credential(
        identity: PackageIdentity,
        origin: String,
        name: String,
    ): BasicCredential? {
        val source = sources.getValue(identity)
        val settings = resolvedServiceSettings()
        val candidates =
            source
                .httpBindings()
                .filter { it.credential == name }
                .mapNotNull { binding ->
                    settings.bindings
                        .singleOrNull { it.packageInstance == identity.id && it.sourceOrigin == binding.origin }
                        ?.let { settings.http[it.service] }
                }.filter { it.origin == origin }
                .distinct()
        require(candidates.size <= 1) { "Package HTTP credential mapping is ambiguous." }
        val service = candidates.singleOrNull()
        return service?.credential?.let(::storedCredential)?.takeIf { it.origin == service.origin }
            ?: legacyCredential(identity)?.takeIf { it.origin == origin }
    }

    fun load(): List<LoadedPackage> =
        sources.map { (identity, source) ->
            val settings = resolvedServiceSettings()
            val packageBindings = settings.bindings.filter { it.packageInstance == identity.id }.associateBy { it.sourceOrigin }
            val origins = packageBindings.mapValues { (_, binding) -> settings.http.getValue(binding.service).origin }
            val configured = configurePackage(source, origins)
            LoadedPackage(
                identity,
                configured,
                configured.httpBindings().all { binding ->
                    binding.credential == null || credential(identity, binding.origin, binding.credential) != null
                },
            )
        }

    fun save(
        id: String,
        sourceOrigin: String,
        serviceName: String,
        url: String,
        username: String,
        password: String,
    ): String? =
        runCatching {
            val identity = sources.keys.single { it.id == id }
            val source = sources.getValue(identity)
            require(source.httpBindings().any { it.origin == sourceOrigin && it.credential != null }) {
                "This package does not declare credentials for that HTTPS origin."
            }
            require(SERVICE_NAME.matches(serviceName)) { "Use a lowercase service name with letters, numbers, and hyphens." }
            val saved = BasicCredential.create(url, username, password)
            val current = resolvedServiceSettings()
            current.http[serviceName]?.let { existing ->
                require(existing.origin == saved.origin) { "Service $serviceName already approves ${existing.origin}." }
            }
            val reference =
                com.colonelpanic.eva.data.configuration.EvaConfigurationCodec
                    .serviceSecretId(serviceName)
            val bindings =
                current.bindings.filterNot { it.packageInstance == id && it.sourceOrigin == sourceOrigin } +
                    PackageServiceBinding(id, sourceOrigin, serviceName)
            val services =
                retainUsedServices(
                    current.http + (serviceName to HttpServiceDefinition(saved.origin, reference)),
                    bindings,
                )
            validateRuntimeMappings(services, bindings)
            secrets.write(serviceSecretKey(serviceName), saved.encode())
            saveServiceSettings(services, bindings)
            mutable.value = entries()
            onCredentialChanged(reference)
            onChanged()
        }.exceptionOrNull()?.let { it.message ?: "Could not save credentials." }

    fun clear(
        id: String,
        sourceOrigin: String,
    ) {
        require(sources.keys.any { it.id == id && sources.getValue(it).httpBindings().any { binding -> binding.origin == sourceOrigin } })
        val current = resolvedServiceSettings()
        val removed = current.bindings.singleOrNull { it.packageInstance == id && it.sourceOrigin == sourceOrigin }
        val bindings = current.bindings.filterNot { it.packageInstance == id && it.sourceOrigin == sourceOrigin }
        val services = retainUsedServices(current.http, bindings)
        removed?.service?.takeIf { it !in services }?.let { service ->
            current.http[service]?.credential?.let(onCredentialChanged)
            secrets.clear(serviceSecretKey(service))
        }
        saveServiceSettings(services, bindings)
        mutable.value = entries()
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

    private fun entries(): List<PackageConfigurationEntry> {
        val settings = resolvedServiceSettings()
        return sources.flatMap { (identity, source) ->
            val configured =
                source
                    .httpBindings()
                    .filter { it.credential != null }
                    .groupBy { it.origin }
                    .map { (sourceOrigin, bindings) ->
                        val serviceBinding =
                            settings.bindings.singleOrNull { it.packageInstance == identity.id && it.sourceOrigin == sourceOrigin }
                        val service = serviceBinding?.let { settings.http[it.service] }
                        PackageConfigurationEntry(
                            identity.id,
                            source.title,
                            sourceOrigin,
                            serviceBinding?.service,
                            service?.origin,
                            bindings
                                .mapNotNull { it.credential }
                                .distinct()
                                .sorted()
                                .joinToString(),
                            override(identity.id),
                            service?.credential?.let(::storedCredential)?.origin == service?.origin,
                        )
                    }
            configured
                .ifEmpty {
                    listOf(
                        PackageConfigurationEntry(identity.id, source.title, "", null, null, null, override(identity.id)),
                    )
                }.map {
                    it.copy(
                        contentAuthorities =
                            source
                                .contentBindings()
                                .map { binding -> binding.authority }
                                .distinct()
                                .sorted(),
                    )
                }
        }
    }

    fun portable(): PortablePackageSettings {
        val configured = resolvedServiceSettings()
        return PortablePackageSettings(
            repositorySources,
            imported().map { PortablePackage(it.identity.id, it.source, it.url, it.json) },
            prefs.all
                .mapNotNull { (key, value) ->
                    if (key.startsWith("wait:") && value is Long) key.removePrefix("wait:") to value else null
                }.toMap(),
            configured.legacy,
            configured.http,
            configured.bindings,
            appliedDefaults(),
            autoEnabled(),
        )
    }

    fun missingCredentials(settings: PortablePackageSettings): List<String> =
        settings.httpServices.values.mapNotNull { service ->
            service.credential?.takeIf { storedCredential(it)?.origin != service.origin }
        } +
            settings.services.mapNotNull { service ->
                val identity = PackageIdentity(service.packageInstance)
                if (legacyCredential(identity)?.origin == service.origin) null else service.credential
            }

    fun validateRestore(restored: PortablePackageSettings): List<InstalledPlugin> =
        restored.installed
            .map { item ->
                val definition = PackageCodec.decode(item.document)
                InstalledPlugin(
                    PackageIdentity(item.instance),
                    PluginRepository.legacySource(item.source),
                    PluginRepository.legacyUrl(item.url),
                    definition,
                    item.document,
                )
            }.also(com.colonelpanic.eva.adapters.declarative.PluginInstallations::validate)

    fun restore(
        restored: PortablePackageSettings,
        beforePreferences: () -> Unit = {},
    ): List<String> {
        val installed = validateRestore(restored)
        val targetSources = installed.associate { it.identity to it.definition }
        val resolved = resolveLegacy(restored, targetSources)
        validateRuntimeMappings(resolved.http, resolved.bindings, targetSources)
        imports.restore(installed)
        beforePreferences()
        savePreferences {
            putString(REPOSITORIES, Json.encodeToString(restored.repositories.distinct()))
            remove("repository")
            prefs.all.keys
                .filter { it.startsWith("instance:") || it.startsWith("auto:") }
                .forEach(::remove)
            restored.autoEnabled.forEach { (id, value) -> putBoolean("auto:$id", value) }
            prefs.all.keys
                .filter { it.startsWith("wait:") || it.startsWith("origin:") }
                .forEach(::remove)
            restored.waitMillis.forEach { (id, value) -> putLong("wait:$id", value) }
            if (restored.appliedDefaults.isEmpty()) remove(DEFAULTS) else putString(DEFAULTS, Json.encodeToString(restored.appliedDefaults))
            putString(SERVICES, Json.encodeToString(StoredServiceSettings(resolved.http, resolved.bindings)))
            resolved.legacy.forEach { service -> putString("origin:${service.packageInstance}", service.origin) }
        }
        defaults.value = modeDefaults()
        mutable.value = entries()
        return emptyList()
    }

    private data class ResolvedServiceSettings(
        val http: Map<String, HttpServiceDefinition>,
        val bindings: List<PackageServiceBinding>,
        val legacy: List<HttpServiceBinding> = emptyList(),
    )

    private fun serviceSettings(): StoredServiceSettings =
        prefs.getString(SERVICES, null)?.let { encoded ->
            runCatching { Json.decodeFromString<StoredServiceSettings>(encoded) }.getOrNull()
        } ?: StoredServiceSettings()

    private fun resolvedServiceSettings(): ResolvedServiceSettings {
        val stored = serviceSettings()
        val legacy =
            prefs.all.mapNotNull { (key, value) ->
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
            }
        return resolveLegacy(
            PortablePackageSettings(emptyList(), emptyList(), emptyMap(), legacy, stored.http, stored.bindings),
            sources,
        )
    }

    private fun resolveLegacy(
        settings: PortablePackageSettings,
        definitions: Map<PackageIdentity, PackageDefinition>,
    ): ResolvedServiceSettings {
        val services = settings.httpServices.toMutableMap()
        val bindings = settings.serviceBindings.toMutableList()
        val unresolved = mutableListOf<HttpServiceBinding>()
        settings.services.forEach { legacy ->
            val identity = PackageIdentity(legacy.packageInstance)
            val definition = definitions[identity]
            if (definition == null) {
                unresolved += legacy
            } else {
                val name = legacyServiceName(legacy.packageInstance)
                val credential =
                    com.colonelpanic.eva.data.configuration.EvaConfigurationCodec
                        .serviceSecretId(name)
                services[name] = HttpServiceDefinition(legacy.origin, credential)
                definition.httpBindings().map { it.origin }.distinct().forEach { sourceOrigin ->
                    bindings.removeAll { it.packageInstance == legacy.packageInstance && it.sourceOrigin == sourceOrigin }
                    bindings += PackageServiceBinding(legacy.packageInstance, sourceOrigin, name)
                }
            }
        }
        return ResolvedServiceSettings(services, bindings, unresolved)
    }

    private fun validateRuntimeMappings(
        services: Map<String, HttpServiceDefinition>,
        bindings: List<PackageServiceBinding>,
        definitions: Map<PackageIdentity, PackageDefinition> = sources,
    ) {
        require(bindings.all { it.service in services }) { "Package binding names an unknown HTTP service." }
        require(bindings.map { it.service }.toSet() == services.keys) { "Every HTTP service must be used by a package binding." }
        bindings.groupBy { it.packageInstance }.forEach { (instance, packageBindings) ->
            val definition = definitions[PackageIdentity(instance)] ?: return@forEach
            packageBindings.forEach { binding -> require(definition.httpBindings().any { it.origin == binding.sourceOrigin }) }
            packageBindings.forEach { binding ->
                if (definition.httpBindings().any { it.origin == binding.sourceOrigin && it.credential != null }) {
                    require(services.getValue(binding.service).credential != null) {
                        "A credential-requiring package origin must use a service with a credential reference."
                    }
                }
            }
            definition
                .httpBindings()
                .filter { it.credential != null }
                .mapNotNull { sourceBinding ->
                    val binding = packageBindings.singleOrNull { it.sourceOrigin == sourceBinding.origin } ?: return@mapNotNull null
                    val service = services.getValue(binding.service)
                    Triple(service.origin, sourceBinding.credential, service.credential)
                }.groupBy { it.first to it.second }
                .values
                .forEach { candidates ->
                    require(candidates.map { it.third }.distinct().size == 1) { "Ambiguous HTTP credential mapping." }
                }
        }
    }

    private fun saveServiceSettings(
        services: Map<String, HttpServiceDefinition>,
        bindings: List<PackageServiceBinding>,
    ) {
        savePreferences {
            putString(SERVICES, Json.encodeToString(StoredServiceSettings(services, bindings)))
        }
    }

    private fun retainUsedServices(
        services: Map<String, HttpServiceDefinition>,
        bindings: List<PackageServiceBinding>,
    ): Map<String, HttpServiceDefinition> {
        val used = bindings.map { it.service }.toSet()
        return services.filterKeys { it in used }
    }

    private fun legacyServiceName(instance: String) = "package-$instance"

    private fun serviceSecretKey(name: String) = "service:$name:basic"

    private fun secretKey(reference: String): String =
        when {
            reference.startsWith("service/") -> serviceSecretKey(reference.removePrefix("service/").removeSuffix("/basic"))
            reference.startsWith("package/") -> "package:${reference.removePrefix("package/").removeSuffix("/basic")}:basic"
            else -> error("Unsupported package credential reference.")
        }

    // KTX edit discards the commit result; installation identity must fail closed on write failure.
    @SuppressLint("UseKtx")
    private fun savePreferences(change: SharedPreferences.Editor.() -> Unit) {
        check(prefs.edit().apply(change).commit()) { "Could not save package settings." }
    }

    private companion object {
        const val SERVICES = "services:v2"
        const val DEFAULTS = "defaults"
        const val REPOSITORIES = "repositories"
        val SERVICE_NAME = Regex("[a-z][a-z0-9-]{0,63}")
    }
}
