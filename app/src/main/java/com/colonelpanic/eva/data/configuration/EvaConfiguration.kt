package com.colonelpanic.eva.data.configuration

import com.charleskorn.kaml.MultiLineStringStyle
import com.charleskorn.kaml.SingleLineStringStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlException
import com.colonelpanic.eva.adapters.declarative.PackageCodec
import com.colonelpanic.eva.adapters.declarative.httpBindings
import com.colonelpanic.eva.conversation.prompt.PromptComponent
import com.colonelpanic.eva.conversation.prompt.PromptConfig
import com.colonelpanic.eva.conversation.prompt.PromptDefaults
import com.colonelpanic.eva.providers.openai.OpenAiModels
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

@Serializable
data class EvaConfigurationDocument(
    @Required val format: String = FORMAT,
    @Required val version: Int = VERSION,
    val include: List<String> = emptyList(),
    val models: ModelsPatch? = null,
    val voice: VoicePatch? = null,
    val appearance: AppearancePatch? = null,
    val capabilities: CapabilitiesPatch? = null,
    val messaging: MessagingPatch? = null,
    val prompt: PromptPatch? = null,
    val packages: PackagesPatch? = null,
    val services: ServicesPatch? = null,
    val extensions: ExtensionsPatch? = null,
    val spotify: SpotifyPatch? = null,
    val credentials: CredentialsPatch? = null,
    val remembered: RememberedPatch? = null,
    val device: DevicePatch? = null,
) {
    companion object {
        const val FORMAT = "eva"
        const val VERSION = 3
        const val OLDEST_SUPPORTED_VERSION = 1
    }
}

@Serializable data class ModelsPatch(
    val text: String? = null,
    val realtime: String? = null,
    val reasoningEffort: String? = null,
    val voiceReasoningEffort: String? = null,
)

@Serializable data class VoicePatch(
    val lookupRetries: Int? = null,
    val oneShotExternal: Boolean? = null,
)

@Serializable data class AppearancePatch(
    val dynamicColor: Boolean? = null,
)

@Serializable data class CapabilitiesPatch(
    val screenControl: Boolean? = null,
)

@Serializable data class MessagingPatch(
    val enabled: Boolean? = null,
    val replies: List<String>? = null,
)

@Serializable data class PromptPatch(
    val source: String? = null,
    val components: List<PromptComponent>? = null,
)

@Serializable
data class PackagesPatch(
    val repository: String? = null,
    @SerialName("bundledInstances") val legacyBundledInstances: Map<String, String>? = null,
    val installed: List<PortablePackage>? = null,
    val waitMillis: Map<String, Long>? = null,
    /** Version-1 per-package services, retained only for migration. */
    val services: List<HttpServiceBinding>? = null,
    val serviceBindings: List<PackageServiceBinding>? = null,
    val appliedDefaults: List<String>? = null,
)

@Serializable
data class PortablePackage(
    val instance: String,
    val source: String,
    val url: String,
    val document: String,
)

@Serializable
data class HttpServiceBinding(
    val packageInstance: String,
    val origin: String,
    val credential: String,
)

@Serializable data class ServicesPatch(
    val http: Map<String, HttpServiceDefinition>? = null,
)

@Serializable
data class HttpServiceDefinition(
    val origin: String,
    val credential: String? = null,
)

@Serializable
data class PackageServiceBinding(
    val packageInstance: String,
    val sourceOrigin: String,
    val service: String,
)

@Serializable data class ExtensionsPatch(
    val grants: List<PortableGrant>? = null,
)

@Serializable
data class PortableGrant(
    val instance: String,
    val identity: String,
    val digest: String,
    val mutations: List<String> = emptyList(),
)

@Serializable data class SpotifyPatch(
    val clientId: String? = null,
    val clearClientId: Boolean = false,
)

@Serializable data class CredentialsPatch(
    val required: List<SecretReference>? = null,
)

@Serializable
data class SecretReference(
    val id: String,
    val kind: String,
    val endpoint: String? = null,
)

@Serializable data class RememberedPatch(
    val chosenNumbers: Map<String, Long>? = null,
)

@Serializable data class DevicePatch(
    val authorizations: List<String>? = null,
)

data class EvaConfiguration(
    val models: Models,
    val voice: Voice,
    val appearance: Appearance,
    val capabilities: Capabilities,
    val messaging: Messaging,
    val prompt: Prompt,
    val packages: Packages,
    val services: Services,
    val extensions: Extensions,
    val spotify: Spotify,
    val credentials: Credentials,
    val remembered: Remembered,
    val device: Device,
) {
    data class Models(
        val text: String,
        val realtime: String,
        val reasoningEffort: String,
        val voiceReasoningEffort: String,
    )

    data class Voice(
        val lookupRetries: Int,
        val oneShotExternal: Boolean = true,
    )

    data class Appearance(
        val dynamicColor: Boolean,
    )

    data class Capabilities(
        val screenControl: Boolean,
    )

    data class Messaging(
        val enabled: Boolean,
        val replies: List<String>,
    )

    data class Prompt(
        val source: String,
        val components: List<PromptComponent>,
    )

    data class Packages(
        val repository: String,
        val installed: List<PortablePackage>,
        val waitMillis: Map<String, Long>,
        /** Version-1 per-package services that could not yet be migrated. */
        val services: List<HttpServiceBinding>,
        val serviceBindings: List<PackageServiceBinding> = emptyList(),
        val legacyBundledInstances: Map<String, String> = emptyMap(),
        /** Shipped default packages already installed once; EVA never reinstalls a default listed here. */
        val appliedDefaults: List<String> = emptyList(),
    )

    data class Services(
        val http: Map<String, HttpServiceDefinition>,
    )

    data class Extensions(
        val grants: List<PortableGrant>,
    )

    data class Spotify(
        val clientId: String?,
    )

    data class Credentials(
        val required: List<SecretReference>,
    )

    data class Remembered(
        val chosenNumbers: Map<String, Long>,
    )

    data class Device(
        val authorizations: List<String>,
    )
}

data class ResolvedConfiguration(
    val configuration: EvaConfiguration,
    val root: EvaConfigurationDocument,
    val included: EvaConfigurationDocument,
    val fingerprint: String,
    val rootFingerprint: String,
    val includedFingerprint: String,
    val rootText: String,
    val paths: Set<String>,
)

fun interface ConfigurationReader {
    fun read(path: String): String?
}

object EvaConfigurationCodec {
    const val FILE_NAME = "eva.yaml"
    const val MAX_FILE_BYTES = 4_194_304
    const val MAX_GRAPH_BYTES = 8_388_608
    private const val MAX_FILES = 32
    private const val MAX_DEPTH = 8

    private val yaml =
        Yaml(
            configuration =
                YamlConfiguration(
                    encodeDefaults = false,
                    multiLineStringStyle = MultiLineStringStyle.Literal,
                    singleLineStringStyle = SingleLineStringStyle.PlainExceptAmbiguous,
                    breakScalarsAt = 100,
                ),
        )

    fun encode(document: EvaConfigurationDocument): String =
        yaml.encodeToString(EvaConfigurationDocument.serializer(), canonical(document)).trimEnd() + "\n"

    fun decode(text: String): EvaConfigurationDocument {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) { "Configuration file is too large." }
        val document =
            try {
                yaml.decodeFromString(EvaConfigurationDocument.serializer(), text)
            } catch (error: YamlException) {
                throw IllegalArgumentException("Line ${error.line}, column ${error.column}: ${error.message}", error)
            }
        require(document.format == EvaConfigurationDocument.FORMAT) { "This is not an EVA configuration file." }
        require(document.version in EvaConfigurationDocument.OLDEST_SUPPORTED_VERSION..EvaConfigurationDocument.VERSION) {
            "Unsupported EVA configuration version ${document.version}."
        }
        require(document.version < 3 || document.packages?.legacyBundledInstances == null) {
            "packages.bundledInstances was removed in EVA configuration version 3."
        }
        document.include.forEach(::validateInclude)
        require(document.include.distinct().size == document.include.size) { "An include is listed more than once." }
        return document
    }

    fun resolve(
        rootPath: String = FILE_NAME,
        reader: ConfigurationReader,
    ): ResolvedConfiguration {
        val visited = linkedSetOf<String>()
        val paths = linkedSetOf(rootPath)
        val digest = MessageDigest.getInstance("SHA-256")
        val includedDigest = MessageDigest.getInstance("SHA-256")
        var files = 0
        var totalBytes = 0

        fun load(
            path: String,
            depth: Int,
        ): EvaConfigurationDocument {
            require(depth <= MAX_DEPTH) { "Configuration includes are nested too deeply." }
            require(visited.add(path)) { "Configuration include cycle at $path." }
            paths += path
            require(++files <= MAX_FILES) { "Configuration includes too many files." }
            val text = requireNotNull(reader.read(path)) { "Configuration include $path was not found." }
            totalBytes += text.toByteArray(Charsets.UTF_8).size
            require(totalBytes <= MAX_GRAPH_BYTES) { "Configuration include graph is too large." }
            digest.update(path.toByteArray(Charsets.UTF_8))
            digest.update(byteArrayOf(0))
            digest.update(text.toByteArray(Charsets.UTF_8))
            includedDigest.update(path.toByteArray(Charsets.UTF_8))
            includedDigest.update(byteArrayOf(0))
            includedDigest.update(text.toByteArray(Charsets.UTF_8))
            val document = decode(text)
            var merged = EvaConfigurationDocument()
            document.include.forEach { child -> merged = merge(merged, load(resolvePath(path, child), depth + 1)) }
            visited.remove(path)
            return merge(merged, document.copy(include = emptyList()))
        }

        val rootText = requireNotNull(reader.read(rootPath)) { "Configuration file $rootPath was not found." }
        totalBytes += rootText.toByteArray(Charsets.UTF_8).size
        require(totalBytes <= MAX_GRAPH_BYTES) { "Configuration include graph is too large." }
        val root = decode(rootText)
        val included =
            root.include.fold(EvaConfigurationDocument()) { result, child ->
                merge(result, load(resolvePath(rootPath, child), 1))
            }
        digest.update(rootPath.toByteArray(Charsets.UTF_8))
        digest.update(byteArrayOf(0))
        digest.update(rootText.toByteArray(Charsets.UTF_8))
        val resolved = merge(included, root.copy(include = emptyList())).materialize().validated()
        return ResolvedConfiguration(
            resolved,
            root,
            included,
            digest.digest().hex(),
            fingerprint(rootText),
            includedDigest.digest().hex(),
            rootText,
            paths.toSet(),
        )
    }

    fun resolve(reader: ConfigurationReader): ResolvedConfiguration = resolve(FILE_NAME, reader)

    fun complete(configuration: EvaConfiguration): EvaConfigurationDocument = diff(configuration, null)

    fun overrides(
        configuration: EvaConfiguration,
        included: EvaConfigurationDocument,
        include: List<String>,
    ): EvaConfigurationDocument = diff(configuration, included).copy(include = include)

    private fun diff(
        current: EvaConfiguration,
        base: EvaConfigurationDocument?,
    ) = EvaConfigurationDocument(
        models =
            ModelsPatch(
                current.models.text.takeIf { it != base?.models?.text },
                current.models.realtime.takeIf { it != base?.models?.realtime },
                current.models.reasoningEffort.takeIf { it != base?.models?.reasoningEffort },
                current.models.voiceReasoningEffort.takeIf { it != base?.models?.voiceReasoningEffort },
            ).nonEmpty(),
        voice =
            VoicePatch(
                current.voice.lookupRetries.takeIf { it != base?.voice?.lookupRetries },
                current.voice.oneShotExternal.takeIf { it != base?.voice?.oneShotExternal },
            ).nonEmpty(),
        appearance = AppearancePatch(current.appearance.dynamicColor.takeIf { it != base?.appearance?.dynamicColor }).nonEmpty(),
        capabilities = CapabilitiesPatch(current.capabilities.screenControl.takeIf { it != base?.capabilities?.screenControl }).nonEmpty(),
        messaging =
            MessagingPatch(
                current.messaging.enabled.takeIf { it != base?.messaging?.enabled },
                current.messaging.replies.takeIf { it != base?.messaging?.replies },
            ).nonEmpty(),
        prompt =
            PromptPatch(
                current.prompt.source.takeIf { it != base?.prompt?.source },
                current.prompt.components.takeIf { it != base?.prompt?.components },
            ).nonEmpty(),
        packages =
            PackagesPatch(
                current.packages.repository.takeIf { it != base?.packages?.repository },
                null,
                current.packages.installed.takeIf { it != base?.packages?.installed },
                current.packages.waitMillis.takeIf { it != base?.packages?.waitMillis },
                current.packages.services.takeIf { it != base?.packages?.services },
                current.packages.serviceBindings.takeIf { it != base?.packages?.serviceBindings },
                current.packages.appliedDefaults.takeIf { it != base?.packages?.appliedDefaults },
            ).nonEmpty(),
        services = ServicesPatch(current.services.http.takeIf { it != base?.services?.http }).nonEmpty(),
        extensions = ExtensionsPatch(current.extensions.grants.takeIf { it != base?.extensions?.grants }).nonEmpty(),
        spotify =
            when {
                current.spotify.clientId == base?.spotify?.clientId && base?.spotify?.clearClientId != true -> null
                current.spotify.clientId == null -> SpotifyPatch(clearClientId = true)
                else -> SpotifyPatch(clientId = current.spotify.clientId)
            },
        credentials = CredentialsPatch(current.credentials.required.takeIf { it != base?.credentials?.required }).nonEmpty(),
        remembered = RememberedPatch(current.remembered.chosenNumbers.takeIf { it != base?.remembered?.chosenNumbers }).nonEmpty(),
        device = DevicePatch(current.device.authorizations.takeIf { it != base?.device?.authorizations }).nonEmpty(),
    )

    private fun EvaConfigurationDocument.materialize(): EvaConfiguration =
        EvaConfiguration(
            models =
                EvaConfiguration.Models(
                    requireNotNull(models?.text) { "models.text is missing." },
                    requireNotNull(models?.realtime) { "models.realtime is missing." },
                    requireNotNull(models?.reasoningEffort) { "models.reasoningEffort is missing." },
                    // Documents written before the speech leg had its own effort still load.
                    models?.voiceReasoningEffort ?: OpenAiModels.VOICE_REASONING_EFFORT,
                ),
            voice =
                EvaConfiguration.Voice(
                    requireNotNull(voice?.lookupRetries) { "voice.lookupRetries is missing." },
                    voice.oneShotExternal ?: true,
                ),
            appearance = EvaConfiguration.Appearance(requireNotNull(appearance?.dynamicColor) { "appearance.dynamicColor is missing." }),
            capabilities =
                EvaConfiguration.Capabilities(requireNotNull(capabilities?.screenControl) { "capabilities.screenControl is missing." }),
            messaging =
                EvaConfiguration.Messaging(
                    requireNotNull(messaging?.enabled) { "messaging.enabled is missing." },
                    requireNotNull(messaging?.replies) { "messaging.replies is missing." },
                ),
            prompt =
                EvaConfiguration.Prompt(
                    requireNotNull(prompt?.source) { "prompt.source is missing." },
                    requireNotNull(prompt?.components) { "prompt.components is missing." },
                ),
            packages =
                EvaConfiguration.Packages(
                    requireNotNull(packages?.repository) { "packages.repository is missing." },
                    requireNotNull(packages?.installed) { "packages.installed is missing." },
                    requireNotNull(packages?.waitMillis) { "packages.waitMillis is missing." },
                    packages?.services.orEmpty(),
                    packages?.serviceBindings.orEmpty(),
                    packages?.legacyBundledInstances.orEmpty(),
                    packages?.appliedDefaults.orEmpty(),
                ),
            services = EvaConfiguration.Services(services?.http.orEmpty()),
            extensions =
                EvaConfiguration.Extensions(requireNotNull(extensions?.grants) { "extensions.grants is missing." }),
            spotify = EvaConfiguration.Spotify(if (spotify?.clearClientId == true) null else spotify?.clientId),
            credentials =
                EvaConfiguration.Credentials(requireNotNull(credentials?.required) { "credentials.required is missing." }),
            remembered =
                EvaConfiguration.Remembered(requireNotNull(remembered?.chosenNumbers) { "remembered.chosenNumbers is missing." }),
            device =
                EvaConfiguration.Device(requireNotNull(device?.authorizations) { "device.authorizations is missing." }),
        )

    private fun EvaConfiguration.validated(): EvaConfiguration {
        models.text.modelName()
        models.realtime.modelName()
        require(models.reasoningEffort in OpenAiModels.TEXT_REASONING_EFFORTS) { "Unknown text reasoning effort." }
        require(models.voiceReasoningEffort in OpenAiModels.VOICE_REASONING_EFFORTS) { "Unknown voice reasoning effort." }
        require(voice.lookupRetries in 0..10) { "voice.lookupRetries must be between 0 and 10." }
        require(messaging.replies.size <= 100) { "At most 100 messaging reply identities may be configured." }
        require(messaging.replies.distinct().size == messaging.replies.size) { "Duplicate messaging reply identity." }
        messaging.replies.forEach { require(MESSAGING_IDENTITY.matches(it)) { "Invalid messaging reply identity." } }
        prompt.source.https("prompt.source")
        PromptConfig(prompt.components).validated(PromptDefaults.VARIABLES)
        packages.repository.https("packages.repository")
        packages.legacyBundledInstances.forEach { (name, id) ->
            require(name.matches(Regex("[A-Za-z0-9._-]{1,200}"))) { "Invalid bundled package name." }
            id.uuid("bundled package")
        }
        require(
            packages.legacyBundledInstances.values
                .distinct()
                .size == packages.legacyBundledInstances.size,
        ) { "Duplicate bundled package instance." }
        require(packages.appliedDefaults.distinct().size == packages.appliedDefaults.size) { "Duplicate applied default package." }
        packages.appliedDefaults.forEach { require(it.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Invalid applied default package." } }
        require(packages.installed.size <= 64) { "At most 64 packages may be installed." }
        packages.installed.forEach { item ->
            item.instance.uuid("package")
            require(item.source.length in 1..2048 && item.url.length in 1..2048)
            PackageCodec.decode(item.document)
        }
        require(
            packages.installed
                .map { it.instance }
                .distinct()
                .size == packages.installed.size,
        ) { "Duplicate package instance." }
        val packageIds = packages.legacyBundledInstances.values.toSet() + packages.installed.map { it.instance }
        packages.waitMillis.forEach { (id, millis) ->
            require(id in setOf("voice", "typed") || id in packageIds) { "Wait budget names an unknown package." }
            require(millis in 1_000..60_000) { "Wait budgets must be 1–60 seconds." }
        }
        packages.services.forEach { service ->
            require(service.packageInstance in packageIds) { "HTTP service names an unknown package." }
            service.origin.httpsOrigin()
            require(
                service.credential == packageSecretId(service.packageInstance),
            ) { "Package credential reference is not scoped to its instance." }
        }
        require(
            packages.services
                .map { it.packageInstance }
                .distinct()
                .size == packages.services.size,
        ) { "Duplicate HTTP service binding." }
        services.http.forEach { (name, service) ->
            require(SERVICE_NAME.matches(name)) { "Invalid HTTP service name." }
            service.origin.httpsOrigin()
            require(service.credential == null || service.credential == serviceSecretId(name)) {
                "HTTP service credential reference is not scoped to its service."
            }
        }
        packages.serviceBindings.forEach { binding ->
            require(binding.packageInstance in packageIds) { "HTTP service binding names an unknown package." }
            binding.sourceOrigin.httpsOrigin()
            require(binding.service in services.http) { "HTTP service binding names an unknown service." }
        }
        require(
            packages.serviceBindings
                .map { it.packageInstance to it.sourceOrigin }
                .distinct()
                .size == packages.serviceBindings.size,
        ) { "Duplicate package HTTP origin binding." }
        require(packages.serviceBindings.map { it.service }.toSet() == services.http.keys) {
            "Every HTTP service must be used by a package binding."
        }
        val installedDefinitions = packages.installed.associate { it.instance to PackageCodec.decode(it.document) }
        packages.serviceBindings.forEach { binding ->
            installedDefinitions[binding.packageInstance]?.let { definition ->
                require(definition.httpOrigins().contains(binding.sourceOrigin)) {
                    "HTTP service binding names an origin not declared by its package."
                }
                if (definition.httpBindings().any { it.origin == binding.sourceOrigin && it.credential != null }) {
                    require(services.http.getValue(binding.service).credential != null) {
                        "A credential-requiring package origin must use a service with a credential reference."
                    }
                }
            }
        }
        extensions.grants.forEach { grant ->
            require(grant.instance.length in 1..500 && grant.identity.length in 1..256 && grant.digest.length == 64)
            require(grant.mutations.distinct().size == grant.mutations.size)
        }
        require(
            extensions.grants
                .map { it.instance }
                .distinct()
                .size == extensions.grants.size,
        ) { "Duplicate extension grant." }
        spotify.clientId?.let {
            require(
                it.isNotBlank() && it.length <= 256 && it.none(Char::isWhitespace),
            ) { "Invalid Spotify client ID." }
        }
        credentials.required.forEach { reference ->
            val expectedKind =
                PROVIDER_SECRETS[reference.id]
                    ?: "http-basic".takeIf { SERVICE_SECRET.matches(reference.id) || PACKAGE_SECRET.matches(reference.id) }
            require(expectedKind != null) { "Unknown or unscoped secret reference." }
            require(reference.kind == expectedKind) { "Secret reference kind does not match its scope." }
            when {
                reference.id == BROKER_SECRET -> {
                    requireNotNull(
                        reference.endpoint,
                    ) { "Broker credential requires its endpoint." }.websocketEndpoint()
                }

                reference.id in PROVIDER_SECRETS -> {
                    require(
                        reference.endpoint == null,
                    ) { "This provider credential does not use an endpoint." }
                }

                else -> {
                    requireNotNull(reference.endpoint) { "HTTP credential requires its service origin." }.httpsOrigin()
                }
            }
        }
        require(
            credentials.required
                .map { it.id }
                .distinct()
                .size == credentials.required.size,
        ) { "Duplicate secret reference." }
        packages.services.forEach { service ->
            require(credentials.required.any { it.id == service.credential && it.endpoint == service.origin }) {
                "HTTP service credential requirement is missing or has a different origin."
            }
        }
        services.http.forEach { (_, service) ->
            service.credential?.let { credential ->
                require(credentials.required.any { it.id == credential && it.endpoint == service.origin }) {
                    "HTTP service credential requirement is missing or has a different origin."
                }
            }
        }
        val declaredCredentials =
            services.http.values
                .mapNotNull { it.credential }
                .toSet() + packages.services.map { it.credential }.toSet()
        credentials.required.filter { SERVICE_SECRET.matches(it.id) || PACKAGE_SECRET.matches(it.id) }.forEach { reference ->
            require(reference.id in declaredCredentials) { "HTTP credential requirement does not belong to a declared service." }
        }
        require(remembered.chosenNumbers.size <= 500)
        remembered.chosenNumbers.forEach { (number, time) ->
            require(number.matches(Regex("[0-9]{7,15}")) && time >= 0) { "Invalid remembered number choice." }
        }
        require(device.authorizations.all { it in DEVICE_AUTHORIZATIONS }) { "Unknown device authorization." }
        require(device.authorizations.distinct().size == device.authorizations.size) { "Duplicate device authorization." }
        return copy(
            packages =
                packages.copy(
                    installed = packages.installed.sortedBy { it.instance },
                    waitMillis = packages.waitMillis.toSortedMap(),
                    services = packages.services.sortedBy { it.packageInstance },
                    serviceBindings = packages.serviceBindings.sortedWith(compareBy({ it.packageInstance }, { it.sourceOrigin })),
                    legacyBundledInstances = packages.legacyBundledInstances.toSortedMap(),
                    appliedDefaults = packages.appliedDefaults.sorted(),
                ),
            services = services.copy(http = services.http.toSortedMap()),
            extensions =
                extensions.copy(
                    grants = extensions.grants.sortedBy { it.instance }.map { it.copy(mutations = it.mutations.sorted()) },
                ),
            messaging = messaging.copy(replies = messaging.replies.sorted()),
            credentials = credentials.copy(required = credentials.required.sortedBy { it.id }),
            remembered = remembered.copy(chosenNumbers = remembered.chosenNumbers.toSortedMap()),
            device = device.copy(authorizations = device.authorizations.sorted()),
        )
    }

    private fun canonical(document: EvaConfigurationDocument) =
        document.copy(
            packages =
                document.packages?.copy(
                    legacyBundledInstances = document.packages.legacyBundledInstances?.toSortedMap(),
                    installed = document.packages.installed?.sortedBy { it.instance },
                    waitMillis = document.packages.waitMillis?.toSortedMap(),
                    services = document.packages.services?.sortedBy { it.packageInstance },
                    serviceBindings =
                        document.packages.serviceBindings?.sortedWith(compareBy({ it.packageInstance }, { it.sourceOrigin })),
                    appliedDefaults = document.packages.appliedDefaults?.sorted(),
                ),
            services = document.services?.copy(http = document.services.http?.toSortedMap()),
            extensions =
                document.extensions?.copy(
                    grants =
                        document.extensions.grants
                            ?.sortedBy { it.instance }
                            ?.map { it.copy(mutations = it.mutations.sorted()) },
                ),
            messaging = document.messaging?.copy(replies = document.messaging.replies?.sorted()),
            credentials = document.credentials?.copy(required = document.credentials.required?.sortedBy { it.id }),
            remembered = document.remembered?.copy(chosenNumbers = document.remembered.chosenNumbers?.toSortedMap()),
            device = document.device?.copy(authorizations = document.device.authorizations?.sorted()),
        )

    private fun merge(
        base: EvaConfigurationDocument,
        override: EvaConfigurationDocument,
    ) = EvaConfigurationDocument(
        models =
            ModelsPatch(
                override.models?.text ?: base.models?.text,
                override.models?.realtime ?: base.models?.realtime,
                override.models?.reasoningEffort ?: base.models?.reasoningEffort,
                override.models?.voiceReasoningEffort ?: base.models?.voiceReasoningEffort,
            ).nonEmpty(),
        voice =
            VoicePatch(
                override.voice?.lookupRetries ?: base.voice?.lookupRetries,
                override.voice?.oneShotExternal ?: base.voice?.oneShotExternal,
            ).nonEmpty(),
        appearance = AppearancePatch(override.appearance?.dynamicColor ?: base.appearance?.dynamicColor).nonEmpty(),
        capabilities = CapabilitiesPatch(override.capabilities?.screenControl ?: base.capabilities?.screenControl).nonEmpty(),
        messaging =
            MessagingPatch(
                override.messaging?.enabled ?: base.messaging?.enabled,
                override.messaging?.replies ?: base.messaging?.replies,
            ).nonEmpty(),
        prompt =
            PromptPatch(
                override.prompt?.source ?: base.prompt?.source,
                override.prompt?.components ?: base.prompt?.components,
            ).nonEmpty(),
        packages =
            PackagesPatch(
                override.packages?.repository ?: base.packages?.repository,
                override.packages?.legacyBundledInstances ?: base.packages?.legacyBundledInstances,
                override.packages?.installed ?: base.packages?.installed,
                override.packages?.waitMillis ?: base.packages?.waitMillis,
                override.packages?.services ?: base.packages?.services,
                override.packages?.serviceBindings ?: base.packages?.serviceBindings,
                override.packages?.appliedDefaults ?: base.packages?.appliedDefaults,
            ).nonEmpty(),
        services = ServicesPatch(override.services?.http ?: base.services?.http).nonEmpty(),
        extensions = ExtensionsPatch(override.extensions?.grants ?: base.extensions?.grants).nonEmpty(),
        spotify =
            when {
                override.spotify?.clearClientId == true -> SpotifyPatch(clearClientId = true)
                override.spotify?.clientId != null -> override.spotify
                else -> base.spotify
            },
        credentials = CredentialsPatch(override.credentials?.required ?: base.credentials?.required).nonEmpty(),
        remembered = RememberedPatch(override.remembered?.chosenNumbers ?: base.remembered?.chosenNumbers).nonEmpty(),
        device = DevicePatch(override.device?.authorizations ?: base.device?.authorizations).nonEmpty(),
    )

    private fun validateInclude(path: String) {
        require(path.length in 1..240 && !path.startsWith('/') && '\\' !in path) { "Includes must be relative paths." }
        val segments = path.split('/')
        require(segments.none { it.isBlank() || it == "." || it == ".." } && segments.all { SEGMENT.matches(it) }) {
            "Include path escapes the selected folder."
        }
    }

    private fun resolvePath(
        parent: String,
        child: String,
    ): String {
        validateInclude(child)
        val prefix = parent.substringBeforeLast('/', "")
        return if (prefix.isEmpty()) child else "$prefix/$child"
    }

    private fun ModelsPatch.nonEmpty() =
        takeIf { text != null || realtime != null || reasoningEffort != null || voiceReasoningEffort != null }

    private fun VoicePatch.nonEmpty() = takeIf { lookupRetries != null || oneShotExternal != null }

    private fun AppearancePatch.nonEmpty() = takeIf { dynamicColor != null }

    private fun CapabilitiesPatch.nonEmpty() = takeIf { screenControl != null }

    private fun MessagingPatch.nonEmpty() = takeIf { enabled != null || replies != null }

    private fun PromptPatch.nonEmpty() = takeIf { source != null || components != null }

    private fun PackagesPatch.nonEmpty() =
        takeIf {
            repository != null || legacyBundledInstances != null || installed != null || waitMillis != null || services != null ||
                serviceBindings != null || appliedDefaults != null
        }

    private fun ServicesPatch.nonEmpty() = takeIf { http != null }

    private fun ExtensionsPatch.nonEmpty() = takeIf { grants != null }

    private fun CredentialsPatch.nonEmpty() = takeIf { required != null }

    private fun RememberedPatch.nonEmpty() = takeIf { chosenNumbers != null }

    private fun DevicePatch.nonEmpty() = takeIf { authorizations != null }

    private fun String.modelName() = require(length in 1..100 && none(Char::isWhitespace)) { "Invalid model name." }

    private fun String.uuid(label: String) = require(UUID.fromString(this).toString() == this) { "Invalid $label instance." }

    private fun String.https(label: String) {
        val uri = URI(this)
        require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.fragment == null) { "$label must be HTTPS." }
    }

    private fun String.httpsOrigin() {
        val uri = URI(this)
        require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.path in listOf("", "/")) {
            "Service endpoint must be an HTTPS origin."
        }
        require(uri.query == null && uri.fragment == null)
    }

    private fun String.websocketEndpoint() {
        val uri = URI(this)
        val secure = uri.scheme == "wss"
        require(
            (secure || (uri.scheme == "ws" && uri.host in setOf("localhost", "127.0.0.1"))) &&
                uri.host != null && uri.port in 1024..65535 && uri.userInfo == null && uri.path == "/device",
        ) {
            "Broker endpoint must use secure WebSocket, except for localhost forwarding."
        }
        require(uri.query == null && uri.fragment == null)
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    fun fingerprint(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).hex()

    private val SEGMENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    private val PACKAGE_SECRET = Regex("package/[0-9a-f-]{36}/basic")
    private val SERVICE_SECRET = Regex("service/[a-z][a-z0-9-]{0,63}/basic")
    private val SERVICE_NAME = Regex("[a-z][a-z0-9-]{0,63}")
    private val MESSAGING_IDENTITY =
        Regex("[0-9]+:[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+:[0-9]+:[0-9a-f]{64}(?:,[0-9a-f]{64})*")
    private val PROVIDER_SECRETS =
        mapOf(
            "provider/openai-api" to "openai-api-key",
            "provider/chatgpt" to "chatgpt-account",
            "provider/broker" to "broker-link",
            "provider/spotify-account" to "spotify-account",
        )
    private const val BROKER_SECRET = "provider/broker"
    val DEVICE_AUTHORIZATIONS =
        setOf(
            "android.permission.RECORD_AUDIO",
            "android.permission.READ_CONTACTS",
            "android.permission.READ_SMS",
            "android.permission.SEND_SMS",
            "android.permission.POST_NOTIFICATIONS",
            "android.role.ASSISTANT",
            "android.notification-listener",
            "shizuku",
            com.colonelpanic.eva.adapters.android.ContentProviderAccess.MOVA_READ_TODOS,
        )

    fun packageSecretId(instance: String) = "package/$instance/basic"

    fun serviceSecretId(name: String) = "service/$name/basic"

    private fun com.colonelpanic.eva.adapters.declarative.PackageDefinition.httpOrigins(): Set<String> =
        httpBindings().map { it.origin }.toSet()
}
