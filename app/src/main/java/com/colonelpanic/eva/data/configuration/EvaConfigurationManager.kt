package com.colonelpanic.eva.data.configuration

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.colonelpanic.eva.EvaApplication
import com.colonelpanic.eva.EvaPermissions
import com.colonelpanic.eva.adapters.android.MediaControlAccess
import com.colonelpanic.eva.assist.AssistantRole
import com.colonelpanic.eva.capability.extensions.ExtensionGrant
import com.colonelpanic.eva.conversation.prompt.PromptConfig
import com.colonelpanic.eva.data.MessagingPreferences
import com.colonelpanic.eva.data.PortablePackageSettings
import com.colonelpanic.eva.providers.BrokerEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

data class ConfigurationStatus(
    val linkedFolder: String? = null,
    val message: String? = null,
    val isError: Boolean = false,
    val setupRequired: List<String> = emptyList(),
)

class EvaConfigurationManager(
    private val app: EvaApplication,
    private val beforeGrantRestore: () -> Unit = {},
    private val availableMessagingReplies: () -> Set<String> = {
        app.notificationMessages.apps.value
            .map { it.identity }
            .toSet()
    },
) {
    private val prefs = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableStatus = MutableStateFlow(ConfigurationStatus())
    val status = mutableStatus.asStateFlow()
    private val linked = LinkedConfiguration(::snapshot, ::apply, ::reassess)
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val changedCredentials = mutableSetOf<String>()
    private val changedGrants = mutableSetOf<String>()
    private val changedMessagingReplies = mutableSetOf<String>()
    private var desired: EvaConfiguration? = null

    @Volatile private var suppressChanges = false

    init {
        scope.launch {
            for (ignored in changes) {
                delay(200)
                while (changes.tryReceive().isSuccess) {
                    // Coalesce only work that has not started.
                }
                runAction { linked.localChange() }
            }
        }
    }

    fun start() {
        val value = prefs.getString(TREE, null) ?: return
        scope.launch { runAction { linked.attach(SafConfigurationDirectory(app, value.toUri())) } }
    }

    fun select(uri: Uri) {
        scope.launch {
            val previous = prefs.getString(TREE, null)
            runAction {
                SafConfigurationDirectory.persistAccess(app.contentResolver, uri)
                check(prefs.edit().putString(TREE, uri.toString()).commit()) { "Could not remember the configuration folder." }
                try {
                    linked.attach(SafConfigurationDirectory(app, uri))
                } catch (error: Exception) {
                    val editor = prefs.edit()
                    if (previous == null) editor.remove(TREE) else editor.putString(TREE, previous)
                    val restored = editor.commit()
                    check(restored) { "Could not restore the previous configuration-folder selection." }
                    throw error
                }
            }
        }
    }

    fun reload() {
        scope.launch { runAction { linked.reload(force = true) } }
    }

    fun reloadOnResume() {
        if (linked.linkedLabel() != null) scope.launch { runAction { linked.reload(force = false) } }
    }

    fun onLocalChange() {
        if (suppressChanges || linked.linkedLabel() == null) return
        changes.trySend(Unit)
    }

    fun onCredentialChange(id: String) {
        synchronized(changedCredentials) { changedCredentials += id }
        onLocalChange()
    }

    fun onGrantChange(instance: String) {
        if (suppressChanges) return
        synchronized(changedGrants) { changedGrants += instance }
        onLocalChange()
    }

    fun onMessagingReplyChange(identity: String) {
        if (suppressChanges) return
        synchronized(changedMessagingReplies) { changedMessagingReplies += identity }
        onLocalChange()
    }

    internal suspend fun attachForTest(directory: ConfigurationDirectory): LinkedConfigurationResult = linked.attach(directory)

    internal suspend fun snapshotForTest(): EvaConfiguration = snapshot()

    internal suspend fun reloadForTest(force: Boolean): LinkedConfigurationResult = linked.reload(force)

    internal suspend fun localChangeForTest(): LinkedConfigurationResult = linked.localChange()

    private suspend fun runAction(action: suspend () -> LinkedConfigurationResult) {
        try {
            when (val result = action()) {
                is LinkedConfigurationResult.Loaded -> {
                    show("Configuration loaded.", result.setupRequired)
                }

                is LinkedConfigurationResult.Saved -> {
                    desired = snapshot()
                    synchronized(changedCredentials) { changedCredentials.clear() }
                    synchronized(changedGrants) { changedGrants.clear() }
                    synchronized(changedMessagingReplies) { changedMessagingReplies.clear() }
                    show("Configuration saved.", result.setupRequired)
                }

                is LinkedConfigurationResult.Conflict -> {
                    show("External configuration changes were loaded. Repeat the in-app edit if it is still wanted.", result.setupRequired)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableStatus.value =
                ConfigurationStatus(
                    linkedFolder = linked.linkedLabel(),
                    message = error.message ?: "Configuration could not be loaded.",
                    isError = true,
                    setupRequired = mutableStatus.value.setupRequired,
                )
        }
    }

    private fun show(
        message: String,
        requirements: List<String>,
    ) {
        mutableStatus.value =
            ConfigurationStatus(
                linkedFolder = linked.linkedLabel(),
                message = message,
                setupRequired = requirements.distinct().sorted(),
            )
    }

    private suspend fun snapshot(): EvaConfiguration {
        val prompt = app.prompts.portableSnapshot()
        val packages = app.packageSettings.portable()
        val observedCredentialRefs =
            buildList {
                if (app.settings.apiKey() != null) add(SecretReference(OPENAI_REF, "openai-api-key"))
                if (app.chatGpt.signedIn) add(SecretReference(CHATGPT_REF, "chatgpt-account"))
                app.settings.hostLink().takeIf { it.isNotBlank() }?.let { link ->
                    add(SecretReference(BROKER_REF, "broker-link", BrokerEndpoint.parse(link).socketUrl))
                }
                if (app.spotify.account.value != null) add(SecretReference(SPOTIFY_REF, "spotify-account"))
                packages.services.forEach { service -> add(SecretReference(service.credential, "http-basic", service.origin)) }
            }
        val credentialRefs = retainedCredentials(observedCredentialRefs, packages)
        val liveGrants =
            app.extensions.portableGrants().map { (instance, grant) ->
                PortableGrant(instance, grant.identityKey, grant.digest, grant.mutations.sorted())
            }
        val grantChanges = synchronized(changedGrants) { changedGrants.toSet() }
        val grants =
            (
                desired
                    ?.extensions
                    ?.grants
                    .orEmpty()
                    .filter { it.instance !in grantChanges } + liveGrants
            ).associateBy { it.instance }
                .values
                .toList()
        val messaging = app.messagingSettings.state.value
        val replyChanges = synchronized(changedMessagingReplies) { changedMessagingReplies.toSet() }
        val replies =
            (
                desired
                    ?.messaging
                    ?.replies
                    .orEmpty()
                    .filter { it !in replyChanges } + messaging.replies
            ).distinct()
        return EvaConfiguration(
            models = EvaConfiguration.Models(app.settings.textModel, app.settings.realtimeModel, app.settings.reasoningEffort),
            voice = EvaConfiguration.Voice(app.settings.voiceLookupRetries),
            appearance = EvaConfiguration.Appearance(app.appearance.dynamicColor),
            capabilities = EvaConfiguration.Capabilities(app.capabilities.screenControlEnabled),
            messaging = EvaConfiguration.Messaging(messaging.enabled, replies),
            prompt = EvaConfiguration.Prompt(prompt.source, prompt.config.components),
            packages = packages.configuration(),
            extensions = EvaConfiguration.Extensions(grants),
            spotify = EvaConfiguration.Spotify(app.spotify.clientId.value),
            credentials = EvaConfiguration.Credentials(credentialRefs),
            remembered = EvaConfiguration.Remembered(app.chosenNumbers.all()),
            device = EvaConfiguration.Device(desired?.device?.authorizations ?: currentAuthorizations(includeRequired = true)),
        )
    }

    private suspend fun apply(configuration: EvaConfiguration): ConfigurationApplyResult {
        val packageTarget = configuration.packages.portable()
        app.packageSettings.validateRestore(packageTarget)
        val promptBefore = app.prompts.portableSnapshot()
        val messagingBefore = app.messagingSettings.state.value
        val before = snapshot()
        suppressChanges = true
        try {
            val packageNotices = applyOrdinary(configuration, packageTarget)
            val missingMessagingReplies = restoreMessaging(configuration.messaging)
            val restoredGrants =
                configuration.extensions.grants.associate { grant ->
                    grant.instance to ExtensionGrant(grant.identity, grant.digest, grant.mutations.toSet())
                }
            beforeGrantRestore()
            val missingGrants = app.extensions.restoreGrants(restoredGrants, packageTarget.installed.map { it.instance }.toSet())
            val setup = setupRequirements(configuration, missingGrants, missingMessagingReplies) + packageNotices
            desired = configuration
            synchronized(changedCredentials) { changedCredentials.clear() }
            synchronized(changedGrants) { changedGrants.clear() }
            synchronized(changedMessagingReplies) { changedMessagingReplies.clear() }
            return ConfigurationApplyResult(setup)
        } catch (error: Exception) {
            withContext(NonCancellable) {
                runCatching {
                    applyOrdinary(before, before.packages.portable())
                    app.messagingSettings.replace(messagingBefore)
                    app.prompts.rollback(promptBefore)
                    val grants =
                        before.extensions.grants.associate { grant ->
                            grant.instance to ExtensionGrant(grant.identity, grant.digest, grant.mutations.toSet())
                        }
                    app.extensions.restoreGrants(
                        grants,
                        before.packages.installed
                            .map { it.instance }
                            .toSet(),
                    )
                }.exceptionOrNull()?.let(error::addSuppressed)
            }
            throw error
        } finally {
            suppressChanges = false
        }
    }

    private suspend fun reassess(
        configuration: EvaConfiguration,
        previous: List<String>,
    ): ConfigurationApplyResult {
        val messagingBefore = app.messagingSettings.state.value
        suppressChanges = true
        return try {
            val missingMessagingReplies = restoreMessaging(configuration.messaging)
            val restoredGrants =
                configuration.extensions.grants.associate { grant ->
                    grant.instance to ExtensionGrant(grant.identity, grant.digest, grant.mutations.toSet())
                }
            val missingGrants =
                app.extensions.restoreGrants(
                    restoredGrants,
                    configuration.packages.installed
                        .map { it.instance }
                        .toSet(),
                )
            ConfigurationApplyResult(
                setupRequirements(configuration, missingGrants, missingMessagingReplies) + packageNotices(configuration),
            )
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { app.messagingSettings.replace(messagingBefore) }
            throw cancelled
        } catch (error: Exception) {
            app.messagingSettings.replace(messagingBefore)
            ConfigurationApplyResult(previous + (error.message ?: "Could not reassess extension authorization requirements."))
        } finally {
            suppressChanges = false
        }
    }

    private fun retainedCredentials(
        observed: List<SecretReference>,
        packages: PortablePackageSettings,
    ): List<SecretReference> {
        val changes = synchronized(changedCredentials) { changedCredentials.toSet() }
        val current = observed.associateBy { it.id }
        val retained =
            desired
                ?.credentials
                ?.required
                .orEmpty()
                .associateBy { it.id }
                .toMutableMap()
        changes.forEach { id ->
            retained.remove(id)
            current[id]?.let { retained[id] = it }
        }
        current.forEach { (id, reference) -> retained[id] = reference }
        val serviceIds = packages.services.map { it.credential }.toSet()
        retained.keys.filter { it.startsWith("package/") && it !in serviceIds }.forEach(retained::remove)
        return retained.values.sortedBy { it.id }
    }

    private suspend fun applyOrdinary(
        configuration: EvaConfiguration,
        packages: PortablePackageSettings,
    ): List<String> {
        app.settings.saveTextModel(configuration.models.text)
        app.settings.saveRealtimeModel(configuration.models.realtime)
        app.settings.saveReasoningEffort(configuration.models.reasoningEffort)
        app.settings.saveVoiceLookupRetries(configuration.voice.lookupRetries)
        app.appearance.saveDynamicColor(configuration.appearance.dynamicColor)
        app.capabilities.saveScreenControl(configuration.capabilities.screenControl)
        app.spotify.saveClientId(configuration.spotify.clientId.orEmpty())
        app.prompts.restorePortable(configuration.prompt.source, PromptConfig(configuration.prompt.components))
        val notices = app.packageSettings.restore(packages)
        app.chosenNumbers.replace(configuration.remembered.chosenNumbers)
        return notices
    }

    private fun restoreMessaging(configuration: EvaConfiguration.Messaging): Set<String> {
        val available = availableMessagingReplies()
        val desiredReplies = configuration.replies.toSet()
        app.messagingSettings.replace(MessagingPreferences(configuration.enabled, desiredReplies.intersect(available)))
        return desiredReplies - available
    }

    private fun setupRequirements(
        configuration: EvaConfiguration,
        missingGrants: Set<String>,
        missingMessagingReplies: Set<String>,
    ): List<String> =
        buildList {
            configuration.credentials.required.forEach { reference ->
                val available =
                    when (reference.id) {
                        OPENAI_REF -> {
                            app.settings.apiKey() != null
                        }

                        CHATGPT_REF -> {
                            app.chatGpt.signedIn
                        }

                        BROKER_REF -> {
                            app.settings
                                .hostLink()
                                .takeIf { it.isNotBlank() }
                                ?.let { BrokerEndpoint.parse(it).socketUrl } ==
                                reference.endpoint
                        }

                        SPOTIFY_REF -> {
                            app.spotify.account.value != null
                        }

                        else -> {
                            reference.id !in app.packageSettings.missingCredentials(configuration.packages.services)
                        }
                    }
                if (!available) add("Provision local credential ${reference.id}${reference.endpoint?.let { " for $it" }.orEmpty()}.")
            }
            val current = currentAuthorizations(includeRequired = false).toSet()
            (configuration.device.authorizations - current).forEach { add("Authorize $it on this device.") }
            if (configuration.messaging.enabled && !MediaControlAccess.isGranted(app)) {
                add("Authorize android.notification-listener on this device for messaging notifications.")
            }
            missingMessagingReplies.sorted().forEach {
                add("Reapprove messaging replies for $it; that exact app installation and signer are not currently available.")
            }
            missingGrants.forEach { add("Install or reapprove extension $it; its saved identity or contract is not currently available.") }
        }

    private fun packageNotices(configuration: EvaConfiguration): List<String> {
        val configured = configuration.packages.bundledInstances.keys
        val available =
            app.packageSettings
                .portable()
                .bundledInstances.keys
        return buildList {
            (configured - available).sorted().forEach { add("Bundled package $it is not in this EVA build.") }
            (available - configured).sorted().forEach { add("Bundled package $it is new on this EVA build.") }
        }
    }

    private fun currentAuthorizations(includeRequired: Boolean): List<String> =
        buildList {
            EvaPermissions.REQUIRED.forEach { permission ->
                if (includeRequired ||
                    ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED
                ) {
                    add(permission)
                }
            }
            if (AssistantRole.isEva(app)) add("android.role.ASSISTANT")
            if (MediaControlAccess.isGranted(app)) add("android.notification-listener")
            if (runCatching { Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(
                    false,
                )
            ) {
                add("shizuku")
            }
        }

    private fun PortablePackageSettings.configuration() =
        EvaConfiguration.Packages(repository, bundledInstances, installed, waitMillis, services)

    private fun EvaConfiguration.Packages.portable() =
        PortablePackageSettings(repository, bundledInstances, installed, waitMillis, services)

    private companion object {
        const val PREFERENCES = "eva.configuration"
        const val TREE = "tree"
        const val OPENAI_REF = "provider/openai-api"
        const val CHATGPT_REF = "provider/chatgpt"
        const val BROKER_REF = "provider/broker"
        const val SPOTIFY_REF = "provider/spotify-account"
    }
}
