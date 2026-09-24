package com.colonelpanic.eva.data.configuration

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.colonelpanic.eva.EvaApplication
import com.colonelpanic.eva.EvaPermissions
import com.colonelpanic.eva.adapters.android.ContentProviderAccess
import com.colonelpanic.eva.adapters.android.MediaControlAccess
import com.colonelpanic.eva.assist.AssistantRole
import com.colonelpanic.eva.capability.extensions.ExtensionGrant
import com.colonelpanic.eva.conversation.prompt.PromptConfig
import com.colonelpanic.eva.data.MessagingPreferences
import com.colonelpanic.eva.data.PortablePackageSettings
import com.colonelpanic.eva.data.PromptPersistenceSnapshot
import com.colonelpanic.eva.data.SecretStore
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

data class ConfigurationStatus(
    val linkedFolder: String? = null,
    val message: String? = null,
    val isError: Boolean = false,
    val setupRequired: List<String> = emptyList(),
    val gitEnabled: Boolean = false,
    val git: GitBootstrap = GitBootstrap(),
    val gitCondition: GitCondition = GitCondition.DISABLED,
    val pendingCommits: Int = 0,
    val busy: Boolean = false,
)

class EvaConfigurationManager(
    private val app: EvaApplication,
    private val beforeGrantRestore: () -> Unit = {},
    private val beforePackagePreferenceRestore: () -> Unit = {},
    private val beforeRollbackStep: (String) -> Unit = {},
    private val beforeManagedConnect: suspend () -> Unit = {},
    private val availableMessagingReplies: () -> Set<String> = {
        app.notificationMessages.apps.value
            .map { it.identity }
            .toSet()
    },
) {
    private val prefs = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val secrets = SecretStore(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableStatus = MutableStateFlow(bootstrapStatus())
    val status = mutableStatus.asStateFlow()
    private val linked = LinkedConfiguration(::snapshot, ::apply, ::reassess)
    private val operations = Mutex()
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val changedCredentials = mutableSetOf<String>()
    private val changedGrants = mutableSetOf<String>()
    private val changedMessagingReplies = mutableSetOf<String>()
    private var desired: EvaConfiguration? = null
    private var managedGit: ManagedGitRepository? = null
    private var startup: Job? = null
    private val localReady = kotlinx.coroutines.CompletableDeferred<Unit>()

    suspend fun awaitReady() = localReady.await()

    @Volatile private var suppressChanges = false

    init {
        scope.launch {
            for (ignored in changes) {
                delay(200)
                while (changes.tryReceive().isSuccess) {
                    // Coalesce only work that has not started.
                }
                runAction(pushGit = true) { linked.localChange() }
            }
        }
    }

    fun start() {
        startup =
            scope.launch {
                try {
                    if (gitEnabled()) {
                        operations.withLock { connectGitLocked(enableOnSuccess = false, localOnly = true) }
                    } else {
                        prefs.getString(TREE, null)?.let { value ->
                            runAction { linked.attach(SafConfigurationDirectory(app, value.toUri())) }
                        }
                    }
                    adoptDefaults()
                    localReady.complete(Unit)
                } catch (failure: Throwable) {
                    localReady.completeExceptionally(failure)
                    throw failure
                }
                if (gitEnabled()) connectGit(enableOnSuccess = false)
            }
    }

    /** Runs after the desired configuration is attached so a restored removal of a default is not undone. */
    private suspend fun adoptDefaults() {
        try {
            operations.withLock {
                var adopted = emptyList<com.colonelpanic.eva.adapters.declarative.InstalledPlugin>()
                app.registry.changeAuthorization { adopted = app.packageSettings.adoptDefaults() }
                adopted.forEach { app.extensions.adopt(it.identity) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            android.util.Log.e("EvaConfiguration", "Default packages were not adopted", failure)
        }
    }

    fun select(uri: Uri) {
        scope.launch {
            val previous = prefs.getString(TREE, null)
            val previousGit = managedGit
            runAction {
                SafConfigurationDirectory.persistAccess(app.contentResolver, uri)
                check(prefs.edit().putString(TREE, uri.toString()).commit()) { "Could not remember the configuration folder." }
                try {
                    linked.attach(SafConfigurationDirectory(app, uri)).also { disableGitInternal() }
                } catch (error: Exception) {
                    val editor = prefs.edit()
                    if (previous == null) editor.remove(TREE) else editor.putString(TREE, previous)
                    val restored = editor.commit()
                    check(restored) { "Could not restore the previous configuration-folder selection." }
                    if (gitEnabled() && previousGit != null) linked.attach(previousGit.directory)
                    throw error
                }
            }
        }
    }

    fun reload() {
        if (gitEnabled()) scope.launch { syncGit() } else scope.launch { runAction { linked.reload(force = true) } }
    }

    fun reloadOnResume() {
        if (linked.linkedLabel() != null) scope.launch { runAction { linked.reload(force = false) } }
    }

    fun configureGit(
        remoteUrl: String,
        branch: String,
        authorName: String,
        authorEmail: String,
        username: String,
        token: String,
    ): String? {
        val value = GitBootstrap(remoteUrl.trim(), branch.trim(), authorName.trim(), authorEmail.trim(), username.trim())
        return runCatching {
            validateGitBootstrap(value)
            scope.launch {
                operations.withLock {
                    try {
                        val previousBootstrap = gitBootstrap()
                        val previousToken = secrets.read(GIT_TOKEN)
                        val enableOnSuccess = !gitEnabled()
                        saveGitBootstrap(value)
                        if (token.isNotBlank()) {
                            secrets.write(GIT_TOKEN, token)
                        } else if (previousBootstrap.remoteUrl != value.remoteUrl) {
                            secrets.clear(GIT_TOKEN)
                        }
                        if (!connectGitLocked(enableOnSuccess) && !enableOnSuccess) {
                            saveGitBootstrap(previousBootstrap)
                            if (previousToken == null) secrets.clear(GIT_TOKEN) else secrets.write(GIT_TOKEN, previousToken)
                            managedGit?.close()
                            managedGit =
                                ManagedGitRepository(managedCheckout(), previousBootstrap, token = { previousToken })
                        }
                    } catch (failure: Exception) {
                        publishGitError(failure)
                    }
                }
            }
        }.exceptionOrNull()?.message
    }

    fun setGitEnabled(enabled: Boolean) {
        scope.launch {
            if (enabled) {
                try {
                    validateGitBootstrap(gitBootstrap())
                    connectGit(enableOnSuccess = true)
                } catch (failure: Exception) {
                    publishGitError(failure)
                }
            } else {
                try {
                    operations.withLock {
                        val tree = prefs.getString(TREE, null)
                        val linkedResult =
                            if (tree != null) {
                                linked.attach(SafConfigurationDirectory(app, tree.toUri()))
                            } else {
                                linked.detach()
                                null
                            }
                        disableGitInternal()
                        mutableStatus.value =
                            bootstrapStatus(
                                linkedFolder = linked.linkedLabel(),
                                message = "Managed Git sync disabled.",
                                setupRequired = linkedResult?.requirements().orEmpty(),
                            )
                    }
                } catch (failure: Exception) {
                    publishGitError(failure)
                }
            }
        }
    }

    fun clearGitToken() {
        scope.launch {
            operations.withLock {
                secrets.clear(GIT_TOKEN)
                mutableStatus.value =
                    mutableStatus.value.copy(
                        git = gitBootstrap(),
                        gitCondition = GitCondition.MISSING_CREDENTIALS,
                        message = "Git token removed from this device.",
                    )
            }
        }
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

    internal suspend fun awaitStartupForTest() = startup?.join()

    internal suspend fun attachForTest(directory: ConfigurationDirectory): LinkedConfigurationResult {
        awaitStartupForTest()
        return linked.attach(directory)
    }

    internal suspend fun snapshotForTest(): EvaConfiguration {
        awaitStartupForTest()
        return snapshot()
    }

    internal suspend fun reloadForTest(force: Boolean): LinkedConfigurationResult {
        awaitStartupForTest()
        return linked.reload(force)
    }

    internal suspend fun localChangeForTest(): LinkedConfigurationResult = linked.localChange()

    private suspend fun runAction(
        pushGit: Boolean = false,
        action: suspend () -> LinkedConfigurationResult,
    ) = operations.withLock {
        try {
            when (val result = action()) {
                is LinkedConfigurationResult.Loaded -> {
                    show("Configuration loaded.", result.setupRequired)
                }

                is LinkedConfigurationResult.Saved -> {
                    savedSnapshot()
                    if (pushGit && gitEnabled()) {
                        showGit(requireNotNull(managedGit).commitAndPush(), result.setupRequired)
                    } else {
                        show("Configuration saved.", result.setupRequired)
                    }
                }

                is LinkedConfigurationResult.Conflict -> {
                    show("External configuration changes were loaded. Repeat the in-app edit if it is still wanted.", result.setupRequired)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (gitEnabled()) publishGitError(error) else publishConfigurationError(error)
        }
    }

    private suspend fun connectGit(enableOnSuccess: Boolean) = operations.withLock { connectGitLocked(enableOnSuccess) }

    private suspend fun connectGitLocked(
        enableOnSuccess: Boolean,
        localOnly: Boolean = false,
    ): Boolean {
        mutableStatus.value = bootstrapStatus(message = "Connecting managed Git checkout…", busy = true)
        val token = secrets.read(GIT_TOKEN)
        val previouslyEnabled = gitEnabled()
        var modeActivated = previouslyEnabled
        var switchedLink = false
        var usable = false
        try {
            managedGit?.close()
            val repository = ManagedGitRepository(managedCheckout(), gitBootstrap(), token = { token })
            managedGit = repository
            var localAttachment: LinkedConfigurationResult? = null
            if (previouslyEnabled && repository.directory.read(EvaConfigurationCodec.FILE_NAME) != null) {
                localAttachment = linked.attach(repository.directory)
                usable = true
            }
            if (localOnly) return usable
            beforeManagedConnect()
            val gitResult = repository.connect()
            if (gitResult.condition == GitCondition.CONFLICT) {
                showGit(gitResult, mutableStatus.value.setupRequired)
                return usable
            }
            val linkedResult =
                if (localAttachment != null && gitResult.condition != GitCondition.CLONED &&
                    gitResult.condition != GitCondition.PULLED
                ) {
                    localAttachment
                } else {
                    try {
                        linked.attach(repository.directory).also { switchedLink = true }
                    } catch (failure: Exception) {
                        if (gitResult.condition == GitCondition.CLONED || gitResult.condition == GitCondition.PULLED) {
                            runCatching { repository.rollback(gitResult) }.onFailure(failure::addSuppressed)
                        }
                        throw failure
                    }
                }
            val requirements = linkedResult.requirements()
            usable = true
            if (enableOnSuccess && !previouslyEnabled) {
                saveGitEnabled(true)
                modeActivated = true
            }
            val recovered = repository.commitAndPush()
            val final =
                if (recovered.condition == GitCondition.READY &&
                    gitResult.condition != GitCondition.READY
                ) {
                    gitResult
                } else {
                    recovered
                }
            if (linkedResult is LinkedConfigurationResult.Saved) savedSnapshot()
            showGit(final, requirements)
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (enableOnSuccess && !modeActivated) {
                if (switchedLink) {
                    runCatching {
                        linked.detach()
                        prefs.getString(TREE, null)?.let { linked.attach(SafConfigurationDirectory(app, it.toUri())) }
                    }.onFailure(failure::addSuppressed)
                }
            }
            publishGitError(failure, token)
            return usable
        }
    }

    private suspend fun syncGit() =
        operations.withLock {
            mutableStatus.value = mutableStatus.value.copy(message = "Syncing managed Git checkout…", busy = true, isError = false)
            val token = secrets.read(GIT_TOKEN)
            try {
                val repository =
                    managedGit ?: ManagedGitRepository(managedCheckout(), gitBootstrap(), token = { token }).also { managedGit = it }
                val gitResult = repository.synchronize()
                if (gitResult.condition == GitCondition.CONFLICT) {
                    showGit(gitResult, mutableStatus.value.setupRequired)
                    return@withLock
                }
                val linkedResult =
                    if (gitResult.condition == GitCondition.PULLED || gitResult.condition == GitCondition.CLONED) {
                        try {
                            linked.reload(force = true)
                        } catch (failure: Exception) {
                            runCatching { repository.rollback(gitResult) }.onFailure(failure::addSuppressed)
                            throw failure
                        }
                    } else {
                        linked.reload(force = false)
                    }
                val recovered = repository.commitAndPush()
                val final =
                    if (recovered.condition == GitCondition.READY &&
                        gitResult.condition != GitCondition.READY
                    ) {
                        gitResult
                    } else {
                        recovered
                    }
                showGit(final, linkedResult.requirements())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                publishGitError(failure, token)
            }
        }

    private suspend fun savedSnapshot() {
        desired = snapshot()
        synchronized(changedCredentials) { changedCredentials.clear() }
        synchronized(changedGrants) { changedGrants.clear() }
        synchronized(changedMessagingReplies) { changedMessagingReplies.clear() }
    }

    private fun LinkedConfigurationResult.requirements(): List<String> =
        when (this) {
            is LinkedConfigurationResult.Loaded -> setupRequired
            is LinkedConfigurationResult.Saved -> setupRequired
            is LinkedConfigurationResult.Conflict -> setupRequired
        }

    private fun show(
        message: String,
        requirements: List<String>,
    ) {
        mutableStatus.value =
            bootstrapStatus(
                linkedFolder = linked.linkedLabel(),
                message = message,
                setupRequired = requirements.distinct().sorted(),
            )
    }

    private fun showGit(
        result: ManagedGitResult,
        requirements: List<String>,
    ) {
        mutableStatus.value =
            bootstrapStatus(
                linkedFolder = linked.linkedLabel(),
                message = result.message,
                isError = result.condition == GitCondition.CONFLICT || result.condition == GitCondition.ERROR,
                setupRequired = requirements.distinct().sorted(),
                gitCondition = result.condition,
                pendingCommits = result.pendingCommits,
            )
    }

    private fun publishConfigurationError(error: Exception) {
        mutableStatus.value =
            bootstrapStatus(
                linkedFolder = linked.linkedLabel(),
                message = error.message ?: "Configuration could not be loaded.",
                isError = true,
                setupRequired = mutableStatus.value.setupRequired,
            )
    }

    private fun publishGitError(
        error: Throwable,
        token: String? = secrets.read(GIT_TOKEN),
    ) {
        val credentialsMissing = token.isNullOrBlank() && gitBootstrap().remoteUrl.startsWith("https://", ignoreCase = true)
        val pending = runCatching { managedGit?.pendingCommits() ?: 0 }.getOrDefault(0)
        val baseMessage = safeGitError(error, token)
        val message =
            if (pending > 0) {
                "$baseMessage Git sync did not complete; $pending local configuration ${if (pending == 1) "commit remains" else "commits remain"}."
            } else {
                baseMessage
            }
        mutableStatus.value =
            bootstrapStatus(
                linkedFolder = linked.linkedLabel(),
                message = if (credentialsMissing) "$message No Git token is saved on this device." else message,
                isError = true,
                setupRequired = mutableStatus.value.setupRequired,
                gitCondition = if (credentialsMissing) GitCondition.MISSING_CREDENTIALS else GitCondition.ERROR,
                pendingCommits = pending,
            )
    }

    private fun gitEnabled(): Boolean = prefs.getBoolean(GIT_ENABLED, false)

    private fun gitBootstrap(): GitBootstrap =
        GitBootstrap(
            remoteUrl = prefs.getString(GIT_REMOTE, "").orEmpty(),
            branch = prefs.getString(GIT_BRANCH, "main") ?: "main",
            authorName = prefs.getString(GIT_AUTHOR_NAME, "EVA on Android") ?: "EVA on Android",
            authorEmail = prefs.getString(GIT_AUTHOR_EMAIL, "eva@localhost") ?: "eva@localhost",
            username = prefs.getString(GIT_USERNAME, "").orEmpty(),
            tokenPresent = secrets.read(GIT_TOKEN) != null,
        )

    @SuppressLint("UseKtx")
    private fun saveGitBootstrap(value: GitBootstrap) {
        check(
            prefs
                .edit()
                .putString(GIT_REMOTE, value.remoteUrl)
                .putString(GIT_BRANCH, value.branch)
                .putString(GIT_AUTHOR_NAME, value.authorName)
                .putString(GIT_AUTHOR_EMAIL, value.authorEmail)
                .putString(GIT_USERNAME, value.username)
                .commit(),
        ) { "Could not save Git configuration." }
    }

    private fun bootstrapStatus(
        linkedFolder: String? = null,
        message: String? = null,
        isError: Boolean = false,
        setupRequired: List<String> = emptyList(),
        gitCondition: GitCondition = if (gitEnabled()) GitCondition.READY else GitCondition.DISABLED,
        pendingCommits: Int = 0,
        busy: Boolean = false,
    ) = ConfigurationStatus(
        linkedFolder = linkedFolder,
        message = message,
        isError = isError,
        setupRequired = setupRequired,
        gitEnabled = gitEnabled(),
        git = gitBootstrap(),
        gitCondition = gitCondition,
        pendingCommits = pendingCommits,
        busy = busy,
    )

    private fun managedCheckout(): java.io.File {
        val bootstrap = gitBootstrap()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val identity =
            digest.digest("${bootstrap.remoteUrl}\u0000${bootstrap.branch}".toByteArray()).take(12).joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            }
        return java.io.File(java.io.File(app.getExternalFilesDir(null) ?: app.filesDir, MANAGED_CHECKOUT), identity).also {
            require(it.isDirectory || it.mkdirs()) { "Could not create the managed Git checkout." }
        }
    }

    private fun disableGitInternal() {
        saveGitEnabled(false)
        managedGit?.close()
        managedGit = null
    }

    @SuppressLint("UseKtx")
    private fun saveGitEnabled(enabled: Boolean) {
        check(prefs.edit().putBoolean(GIT_ENABLED, enabled).commit()) { "Could not update managed Git mode." }
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
                packages.httpServices.values.forEach { service ->
                    service.credential?.let { add(SecretReference(it, "http-" + it.substringAfterLast("/"), service.origin)) }
                }
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
            models =
                EvaConfiguration.Models(
                    app.settings.textModel,
                    app.settings.realtimeModel,
                    app.settings.reasoningEffort,
                    app.settings.voiceReasoningEffort,
                ),
            voice = EvaConfiguration.Voice(app.settings.voiceLookupRetries, app.settings.quietHangUpSeconds),
            appearance = EvaConfiguration.Appearance(app.appearance.dynamicColor),
            capabilities = EvaConfiguration.Capabilities(app.capabilities.screenControlEnabled),
            messaging = EvaConfiguration.Messaging(messaging.enabled, replies),
            prompt = EvaConfiguration.Prompt(prompt.source, prompt.config.components),
            packages = packages.configuration(),
            services = EvaConfiguration.Services(packages.httpServices),
            extensions = EvaConfiguration.Extensions(grants),
            spotify = EvaConfiguration.Spotify(app.spotify.clientId.value),
            credentials = EvaConfiguration.Credentials(credentialRefs),
            remembered = EvaConfiguration.Remembered(app.chosenNumbers.all()),
            device =
                EvaConfiguration.Device(
                    (
                        (
                            desired?.device?.authorizations ?: currentAuthorizations(
                                includeRequired = true,
                            )
                        ) + contentAuthorizations()
                    ).distinct().sorted(),
                ),
        )
    }

    private suspend fun apply(configuration: EvaConfiguration): ConfigurationApplyResult {
        val packageTarget = configuration.packages.portable(configuration.services)
        app.packageSettings.validateRestore(packageTarget)
        val promptBefore = app.prompts.portableSnapshot()
        val messagingBefore = app.messagingSettings.state.value
        val before = snapshot()
        suppressChanges = true
        try {
            val restoreNotices = applyOrdinary(configuration, packageTarget, beforePackagePreferenceRestore)
            val missingMessagingReplies = restoreMessaging(configuration.messaging)
            val restoredGrants =
                configuration.extensions.grants.associate { grant ->
                    grant.instance to ExtensionGrant(grant.identity, grant.digest, grant.mutations.toSet())
                }
            beforeGrantRestore()
            val missingGrants = app.extensions.restoreGrants(restoredGrants, packageTarget.installed.map { it.instance }.toSet())
            val setup =
                setupRequirements(configuration, missingGrants, missingMessagingReplies) + restoreNotices + packageNotices(configuration)
            desired = configuration
            synchronized(changedCredentials) { changedCredentials.clear() }
            synchronized(changedGrants) { changedGrants.clear() }
            synchronized(changedMessagingReplies) { changedMessagingReplies.clear() }
            return ConfigurationApplyResult(setup)
        } catch (error: Exception) {
            val rollbackFailures = withContext(NonCancellable) { rollback(before, promptBefore, messagingBefore) }
            if (rollbackFailures.isEmpty()) throw error
            val failure =
                IllegalStateException(
                    "${error.message ?: "Configuration restore failed."} Rollback was incomplete: " +
                        rollbackFailures.joinToString { it.first },
                    error,
                )
            rollbackFailures.forEach { (_, cause) -> failure.addSuppressed(cause) }
            throw failure
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
        val serviceIds =
            packages.httpServices.values
                .mapNotNull { it.credential }
                .toSet() + packages.services.map { it.credential }
        retained.keys
            .filter { (it.startsWith("package/") || it.startsWith("service/")) && it !in serviceIds }
            .forEach(retained::remove)
        return retained.values.sortedBy { it.id }
    }

    private suspend fun applyOrdinary(
        configuration: EvaConfiguration,
        packages: PortablePackageSettings,
        beforePackagePreferences: () -> Unit = {},
    ): List<String> {
        app.settings.saveTextModel(configuration.models.text)
        app.settings.saveRealtimeModel(configuration.models.realtime)
        app.settings.saveReasoningEffort(configuration.models.reasoningEffort)
        app.settings.saveVoiceReasoningEffort(configuration.models.voiceReasoningEffort)
        app.settings.saveVoiceLookupRetries(configuration.voice.lookupRetries)
        app.settings.saveQuietHangUpSeconds(configuration.voice.quietHangUpSeconds)
        app.appearance.saveDynamicColor(configuration.appearance.dynamicColor)
        app.capabilities.saveScreenControl(configuration.capabilities.screenControl)
        app.spotify.saveClientId(configuration.spotify.clientId.orEmpty())
        app.prompts.restorePortable(configuration.prompt.source, PromptConfig(configuration.prompt.components))
        val notices = app.packageSettings.restore(packages, beforePackagePreferences)
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
                            reference.id !in app.packageSettings.missingCredentials(configuration.packages.portable(configuration.services))
                        }
                    }
                if (!available) add("Provision local credential ${reference.id}${reference.endpoint?.let { " for $it" }.orEmpty()}.")
            }
            app.packageSettings.state.value.flatMap { it.contentAuthorities }.distinct().forEach { authority ->
                ContentProviderAccess.inspect(app, authority).problem?.let { add(it) }
            }
            val current = currentAuthorizations(includeRequired = false).toSet()
            val providerPermissions = ContentProviderAccess.supportedPermissions - EvaPermissions.REQUIRED.toSet()
            val declared = contentAuthorizations().toSet()
            // A saved provider permission matters only while an installed provider still declares it.
            (configuration.device.authorizations - current)
                .filter { it !in providerPermissions || it in declared }
                .forEach { add("Authorize $it on this device.") }
            if (configuration.messaging.enabled && !MediaControlAccess.isGranted(app)) {
                add("Authorize android.notification-listener on this device for messaging notifications.")
            }
            missingMessagingReplies.sorted().forEach {
                add("Reapprove messaging replies for $it; that exact app installation and signer are not currently available.")
            }
            missingGrants.forEach { add("Install or reapprove extension $it; its saved identity or contract is not currently available.") }
        }

    private fun packageNotices(configuration: EvaConfiguration): List<String> =
        configuration.packages.legacyBundledInstances.keys.sorted().map {
            "Bundled package $it was removed. Browse to reinstall and reapprove it."
        }

    private fun contentAuthorizations(): List<String> =
        app.packageSettings.state.value
            .flatMap { it.contentAuthorities }
            .distinct()
            .mapNotNull { ContentProviderAccess.requiredPermission(app, it) }
            .distinct()

    private fun currentAuthorizations(includeRequired: Boolean): List<String> =
        buildList {
            (
                EvaPermissions.REQUIRED.toSet() + contentAuthorizations() +
                    desired
                        ?.device
                        ?.authorizations
                        .orEmpty()
                        .filter { it in ContentProviderAccess.supportedPermissions }
            ).forEach { permission ->
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
        EvaConfiguration.Packages(
            repositories,
            installed,
            waitMillis,
            services,
            serviceBindings,
            appliedDefaults = appliedDefaults,
            autoEnabled = autoEnabled,
        )

    private fun EvaConfiguration.Packages.portable(
        services: EvaConfiguration.Services = EvaConfiguration.Services(emptyMap()),
    ): PortablePackageSettings {
        val installedInstances = installed.mapTo(mutableSetOf(), PortablePackage::instance)
        return PortablePackageSettings(
            repositories,
            installed,
            waitMillis.filterKeys { it in setOf("voice", "typed") || it in installedInstances },
            this.services.filter { it.packageInstance in installedInstances },
            services.http,
            serviceBindings.filter { it.packageInstance in installedInstances },
            appliedDefaults,
            autoEnabled,
        )
    }

    private suspend fun rollback(
        before: EvaConfiguration,
        promptBefore: PromptPersistenceSnapshot,
        messagingBefore: MessagingPreferences,
    ): List<Pair<String, Throwable>> {
        val failures = mutableListOf<Pair<String, Throwable>>()

        suspend fun attempt(
            label: String,
            action: suspend () -> Unit,
        ) {
            try {
                beforeRollbackStep(label)
                action()
            } catch (failure: Throwable) {
                failures += label to failure
            }
        }

        attempt("text model") { app.settings.saveTextModel(before.models.text) }
        attempt("realtime model") { app.settings.saveRealtimeModel(before.models.realtime) }
        attempt("text reasoning effort") { app.settings.saveReasoningEffort(before.models.reasoningEffort) }
        attempt("voice reasoning effort") { app.settings.saveVoiceReasoningEffort(before.models.voiceReasoningEffort) }
        attempt("voice lookup retries") { app.settings.saveVoiceLookupRetries(before.voice.lookupRetries) }
        attempt("quiet hang-up") { app.settings.saveQuietHangUpSeconds(before.voice.quietHangUpSeconds) }
        attempt("appearance") { app.appearance.saveDynamicColor(before.appearance.dynamicColor) }
        attempt("capabilities") { app.capabilities.saveScreenControl(before.capabilities.screenControl) }
        attempt("Spotify client") { app.spotify.saveClientId(before.spotify.clientId.orEmpty()) }
        attempt("packages") { app.packageSettings.restore(before.packages.portable(before.services)) }
        attempt("remembered choices") { app.chosenNumbers.replace(before.remembered.chosenNumbers) }
        attempt("messaging") { app.messagingSettings.replace(messagingBefore) }
        attempt("prompt") { app.prompts.rollback(promptBefore) }
        attempt("extension grants") {
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
        }
        return failures
    }

    private companion object {
        const val PREFERENCES = "eva.configuration"
        const val TREE = "tree"
        const val GIT_ENABLED = "git.enabled"
        const val GIT_REMOTE = "git.remote"
        const val GIT_BRANCH = "git.branch"
        const val GIT_AUTHOR_NAME = "git.author.name"
        const val GIT_AUTHOR_EMAIL = "git.author.email"
        const val GIT_USERNAME = "git.username"
        const val GIT_TOKEN = "configuration/git/token"
        const val MANAGED_CHECKOUT = "configuration-git"
        const val OPENAI_REF = "provider/openai-api"
        const val CHATGPT_REF = "provider/chatgpt"
        const val BROKER_REF = "provider/broker"
        const val SPOTIFY_REF = "provider/spotify-account"
    }
}
