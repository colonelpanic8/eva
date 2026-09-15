package com.colonelpanic.eva.data.configuration

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RefLeaseSpec
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.TagOpt
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.File
import java.net.URI
import java.net.URLEncoder

data class GitBootstrap(
    val remoteUrl: String = "",
    val branch: String = "main",
    val authorName: String = "EVA on Android",
    val authorEmail: String = "eva@localhost",
    val username: String = "",
    val tokenPresent: Boolean = false,
)

enum class GitCondition {
    DISABLED,
    READY,
    CLONED,
    PULLED,
    PUSHED,
    LOCAL_AHEAD,
    CONFLICT,
    MISSING_CREDENTIALS,
    ERROR,
}

data class ManagedGitResult(
    val condition: GitCondition,
    val message: String,
    val pendingCommits: Int = 0,
    internal val previousHead: ObjectId? = null,
)

class ManagedGitRepository(
    checkout: File,
    val bootstrap: GitBootstrap,
    private val token: () -> String?,
    private val allowLocalTransportForTests: Boolean = false,
    private val beforePushForTests: () -> Unit = {},
) : AutoCloseable {
    internal val checkout = checkout.canonicalFile
    private val git: Git
    private val repository: Repository
    val directory = FileConfigurationDirectory(checkout, "Managed Git checkout")

    init {
        validateGitBootstrap(bootstrap, allowLocalTransportForTests)
        val metadata = File(checkout, Constants.DOT_GIT)
        git =
            if (metadata.isDirectory) {
                require(metadata.canonicalFile == metadata.absoluteFile) { "The managed Git metadata cannot be a symbolic link." }
                Git.open(checkout)
            } else {
                require(checkout.listFiles().orEmpty().none { it.name != Constants.DOT_GIT }) {
                    "The managed checkout contains files but is not a Git repository."
                }
                Git
                    .init()
                    .setDirectory(checkout)
                    .setInitialBranch(bootstrap.branch)
                    .call()
            }
        repository = git.repository
        configureRepository()
    }

    fun connect(): ManagedGitResult {
        val fetch = fetch()
        val local = localHead()
        val remote = remoteHead()
        if (fetch == FetchState.DELETED && local != null) return deletedRemote(local)
        return when {
            local == null && remote == null -> {
                ManagedGitResult(GitCondition.READY, "Connected to an empty remote.")
            }

            local == null -> {
                validateTree(requireNotNull(remote))
                require(git.status().call().isClean) { "The managed checkout has uncommitted files; clone was not applied." }
                checkoutRemote(requireNotNull(remote))
                ManagedGitResult(GitCondition.CLONED, "Cloned ${bootstrap.branch}.", previousHead = null)
            }

            else -> {
                ensureBranchCheckedOut(local)
                reconcile(local, remote)
            }
        }
    }

    fun synchronize(): ManagedGitResult {
        val fetch = fetch()
        val local = localHead()
        val remote = remoteHead()
        if (fetch == FetchState.DELETED && local != null) return deletedRemote(local)
        if (local == null) {
            if (remote == null) return ManagedGitResult(GitCondition.READY, "Remote branch is empty.")
            validateTree(remote)
            require(git.status().call().isClean) { "The managed checkout has uncommitted files; pull was not applied." }
            checkoutRemote(remote)
            return ManagedGitResult(GitCondition.PULLED, "Pulled ${bootstrap.branch}.", previousHead = null)
        }
        ensureBranchCheckedOut(local)
        return reconcile(local, remote)
    }

    fun commitAndPush(): ManagedGitResult {
        require(fetch() != FetchState.DELETED) {
            "Remote ${bootstrap.branch} was deleted. EVA kept the local configuration and did not recreate it."
        }
        val localBefore = localHead()
        val remoteBefore = remoteHead()
        ensureRemoteStillExpected(localBefore, remoteBefore)
        ensureBranchCheckedOut(localBefore)
        val resolved = EvaConfigurationCodec.resolve(directory)
        val previousPaths = localBefore?.let(::configurationPaths).orEmpty()
        val ownedPaths = (previousPaths + resolved.paths).sorted()
        resetIndex(localBefore)
        ownedPaths.forEach { path -> git.add().addFilepattern(path).call() }
        ownedPaths.forEach { path ->
            git
                .add()
                .setUpdate(true)
                .addFilepattern(path)
                .call()
        }
        val hasChanges =
            if (localBefore == null) {
                repository.readDirCache().entryCount > 0
            } else {
                git
                    .diff()
                    .setCached(true)
                    .call()
                    .any { it.oldPath in ownedPaths || it.newPath in ownedPaths }
            }
        if (hasChanges) {
            val identity = PersonIdent(bootstrap.authorName, bootstrap.authorEmail)
            git
                .commit()
                .setMessage(COMMIT_MESSAGE)
                .setAuthor(identity)
                .setCommitter(identity)
                .setNoVerify(true)
                .call()
        }
        val local = localHead() ?: return ManagedGitResult(GitCondition.READY, "Configuration has no changes to commit.")
        val pending = aheadCount(remoteBefore, local)
        if (pending == 0) return ManagedGitResult(GitCondition.READY, "Configuration is synchronized.")
        if (requiresCredentials() && token().isNullOrBlank()) {
            return ManagedGitResult(
                GitCondition.MISSING_CREDENTIALS,
                "$pending local configuration ${if (pending == 1) "commit is" else "commits are"} pending; add a Git token to push.",
                pending,
            )
        }
        beforePushForTests()
        return push(local, remoteBefore, pending)
    }

    fun rollback(result: ManagedGitResult) {
        val previous = result.previousHead
        if (previous == null) {
            checkout
                .listFiles()
                .orEmpty()
                .filter { it.name != Constants.DOT_GIT }
                .forEach(::deleteRecursively)
            repository.updateRef(Constants.HEAD, true).link(Constants.R_HEADS + "eva-empty-rollback")
            repository.updateRef(localRef()).apply {
                setForceUpdate(true)
                delete()
            }
            repository.updateRef(trackingRef()).apply {
                setForceUpdate(true)
                delete()
            }
            repository.updateRef(Constants.HEAD, true).link(localRef())
        } else {
            git
                .reset()
                .setMode(ResetCommand.ResetType.HARD)
                .setRef(previous.name)
                .call()
        }
    }

    fun pendingCommits(): Int {
        val local = localHead() ?: return 0
        return aheadCount(remoteHead(), local)
    }

    override fun close() = git.close()

    private fun reconcile(
        local: ObjectId,
        remote: ObjectId?,
    ): ManagedGitResult {
        if (remote == null) {
            val pending = aheadCount(null, local)
            return ManagedGitResult(GitCondition.LOCAL_AHEAD, "$pending local configuration commit(s) are pending.", pending)
        }
        if (local == remote) return ManagedGitResult(GitCondition.READY, "Configuration is synchronized.")
        return when {
            isAncestor(local, remote) -> {
                require(git.status().call().isClean) { "The managed checkout has uncommitted files; pull was not applied." }
                validateTree(remote)
                git
                    .reset()
                    .setMode(ResetCommand.ResetType.HARD)
                    .setRef(remote.name)
                    .call()
                ManagedGitResult(GitCondition.PULLED, "Pulled ${bootstrap.branch}.", previousHead = local)
            }

            isAncestor(remote, local) -> {
                val pending = aheadCount(remote, local)
                ManagedGitResult(GitCondition.LOCAL_AHEAD, "$pending local configuration commit(s) are pending.", pending)
            }

            else -> {
                ManagedGitResult(
                    GitCondition.CONFLICT,
                    "Local and remote ${bootstrap.branch} histories diverged. EVA did not pull or push.",
                    aheadCount(remote, local),
                )
            }
        }
    }

    private fun deletedRemote(local: ObjectId) =
        ManagedGitResult(
            GitCondition.CONFLICT,
            "Remote ${bootstrap.branch} was deleted. EVA kept the local configuration and did not recreate it.",
            aheadCount(null, local),
        )

    private fun checkoutRemote(remote: ObjectId) {
        val existing = repository.resolve(localRef())
        if (existing == null) {
            git
                .checkout()
                .setName(bootstrap.branch)
                .setCreateBranch(true)
                .setStartPoint(remote.name)
                .call()
        } else {
            git.checkout().setName(bootstrap.branch).call()
            git
                .reset()
                .setMode(ResetCommand.ResetType.HARD)
                .setRef(remote.name)
                .call()
        }
    }

    private fun ensureBranchCheckedOut(local: ObjectId?) {
        if (repository.fullBranch == localRef()) return
        require(git.status().call().isClean) { "The managed checkout is on another branch and has uncommitted files." }
        if (local == null) {
            repository.updateRef(Constants.HEAD, true).link(localRef())
        } else {
            git.checkout().setName(bootstrap.branch).call()
        }
    }

    private fun ensureRemoteStillExpected(
        local: ObjectId?,
        remote: ObjectId?,
    ) {
        if (remote == null) return
        require(local != null && (remote == local || isAncestor(remote, local))) {
            "Remote ${bootstrap.branch} changed. EVA kept the local configuration uncommitted; sync before retrying."
        }
    }

    private fun push(
        local: ObjectId,
        expectedRemote: ObjectId?,
        pending: Int,
    ): ManagedGitResult {
        val remoteRef = remoteBranchRef()
        val expected = expectedRemote?.name ?: ObjectId.zeroId().name
        val results =
            git
                .push()
                .setRemote(REMOTE)
                .setRefSpecs(RefSpec("${localRef()}:$remoteRef"))
                .setRefLeaseSpecs(RefLeaseSpec(remoteRef, expected))
                .setCredentialsProvider(credentials())
                .setForce(false)
                .call()
        val update =
            results.flatMap { it.remoteUpdates }.singleOrNull { it.remoteName == remoteRef }
                ?: error("The Git server did not report the branch push result.")
        if (update.status != RemoteRefUpdate.Status.OK && update.status != RemoteRefUpdate.Status.UP_TO_DATE) {
            return ManagedGitResult(
                if (
                    update.status == RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD ||
                    update.status == RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED
                ) {
                    GitCondition.CONFLICT
                } else {
                    GitCondition.LOCAL_AHEAD
                },
                "Push was rejected (${update.status.name.lowercase().replace('_', ' ')}); $pending local configuration commit(s) remain.",
                pending,
            )
        }
        val tracking = repository.updateRef(trackingRef())
        tracking.setExpectedOldObjectId(expectedRemote ?: ObjectId.zeroId())
        tracking.setNewObjectId(local)
        tracking.forceUpdate()
        return ManagedGitResult(GitCondition.PUSHED, "Committed and pushed EVA configuration.")
    }

    private fun fetch(): FetchState {
        val previouslyTracked = remoteHead() != null
        val advertised =
            git
                .lsRemote()
                .setRemote(REMOTE)
                .setHeads(true)
                .setTags(false)
                .setCredentialsProvider(credentials())
                .call()
                .any { it.name == remoteBranchRef() }
        if (!advertised) {
            // Keep an existing tracking ref as the durable evidence that the remote branch
            // was deleted. Otherwise a later save could mistake it for a never-created branch
            // and silently recreate it.
            return if (previouslyTracked) FetchState.DELETED else FetchState.ABSENT
        }
        val result =
            git
                .fetch()
                .setRemote(REMOTE)
                .setRefSpecs(RefSpec("+${remoteBranchRef()}:${trackingRef()}"))
                .setTagOpt(TagOpt.NO_TAGS)
                .setRemoveDeletedRefs(false)
                .setRecurseSubmodules(org.eclipse.jgit.lib.SubmoduleConfig.FetchRecurseSubmodulesMode.NO)
                .setCredentialsProvider(credentials())
                .call()
        val fetchedRemote = result.getAdvertisedRef(remoteBranchRef())
        if (fetchedRemote == null) return if (previouslyTracked) FetchState.DELETED else FetchState.ABSENT
        require(remoteHead() == fetchedRemote.objectId) { "Remote ${bootstrap.branch} changed during fetch; retry sync." }
        return FetchState.PRESENT
    }

    private fun configureRepository() {
        val config = repository.config
        config.getString("eva", "managed", "remote")?.let { configured ->
            require(configured == bootstrap.remoteUrl) { "Managed checkout belongs to a different Git remote." }
        }
        config.getString("eva", "managed", "branch")?.let { configured ->
            require(configured == bootstrap.branch) { "Managed checkout belongs to a different Git branch." }
        }
        config.setString("remote", REMOTE, "url", bootstrap.remoteUrl)
        config.setString("remote", REMOTE, "fetch", "+${remoteBranchRef()}:${trackingRef()}")
        config.setString("core", null, "hooksPath", DISABLED_HOOKS)
        config.setBoolean("http", null, "sslVerify", true)
        config.setString("http", null, "followRedirects", "false")
        config.setString("eva", "managed", "remote", bootstrap.remoteUrl)
        config.setString("eva", "managed", "branch", bootstrap.branch)
        config.save()
        File(repository.directory, DISABLED_HOOKS).mkdirs()
    }

    private fun credentials(): CredentialsProvider? {
        val value = token()?.takeIf { it.isNotBlank() } ?: return null
        return UsernamePasswordCredentialsProvider(bootstrap.username.ifBlank { "git" }, value)
    }

    private fun requiresCredentials(): Boolean = URI(bootstrap.remoteUrl).scheme.equals("https", ignoreCase = true)

    private fun validateTree(head: ObjectId): ResolvedConfiguration? {
        val commit = parseCommit(head)
        var entries = 0
        var bytes = 0L
        TreeWalk(repository).use { walk ->
            walk.addTree(commit.tree)
            walk.isRecursive = true
            while (walk.next()) {
                entries++
                require(entries <= MAX_TREE_ENTRIES) { "The remote checkout contains too many files." }
                val mode = walk.getFileMode(0)
                require(mode != FileMode.SYMLINK) { "The remote checkout contains a symbolic link at ${walk.pathString}." }
                require(mode != FileMode.GITLINK && walk.pathString != Constants.DOT_GIT_MODULES) {
                    "Git submodules are not supported in the managed checkout."
                }
                if (mode.objectType == Constants.OBJ_BLOB) {
                    val loader = repository.open(walk.getObjectId(0), Constants.OBJ_BLOB)
                    bytes += loader.size
                    require(bytes <= MAX_CHECKOUT_BYTES) { "The remote checkout is too large." }
                    if (walk.pathString.endsWith(".gitattributes")) {
                        require(loader.size <= EvaConfigurationCodec.MAX_FILE_BYTES) { "Git attributes file is too large." }
                        require(!loader.bytes.toString(Charsets.UTF_8).contains("filter=lfs", ignoreCase = true)) {
                            "Git LFS attributes are not supported in the managed checkout."
                        }
                    }
                }
            }
        }
        val reader = CommitConfigurationReader(repository, commit)
        if (reader.read(EvaConfigurationCodec.FILE_NAME) == null) return null
        return EvaConfigurationCodec.resolve(reader).also { resolved ->
            resolved.paths.forEach { path ->
                val text = requireNotNull(reader.read(path))
                require(!text.startsWith(LFS_POINTER)) { "Git LFS configuration files are not supported ($path)." }
            }
        }
    }

    private fun configurationPaths(head: ObjectId): Set<String> {
        val reader = CommitConfigurationReader(repository, parseCommit(head))
        if (reader.read(EvaConfigurationCodec.FILE_NAME) == null) return emptySet()
        return EvaConfigurationCodec.resolve(reader).paths
    }

    private fun parseCommit(id: ObjectId): RevCommit = RevWalk(repository).use { it.parseCommit(id) }

    private fun isAncestor(
        ancestor: ObjectId,
        descendant: ObjectId,
    ): Boolean = RevWalk(repository).use { walk -> walk.isMergedInto(walk.parseCommit(ancestor), walk.parseCommit(descendant)) }

    private fun aheadCount(
        remote: ObjectId?,
        local: ObjectId,
    ): Int =
        RevWalk(repository).use { walk ->
            walk.markStart(walk.parseCommit(local))
            remote?.let { walk.markUninteresting(walk.parseCommit(it)) }
            walk.count()
        }

    private fun resetIndex(head: ObjectId?) {
        if (head != null) {
            git
                .reset()
                .setMode(ResetCommand.ResetType.MIXED)
                .setRef(head.name)
                .call()
        } else {
            val index = repository.indexFile
            require(!index.exists() || index.delete()) { "Could not clear the Git index." }
        }
    }

    private fun deleteRecursively(file: File) {
        if (file.canonicalFile == file.absoluteFile && file.isDirectory) {
            file.listFiles().orEmpty().forEach(::deleteRecursively)
        }
        require(file.delete()) { "Could not restore the empty managed checkout." }
    }

    private fun localHead(): ObjectId? = repository.resolve(localRef())

    private fun remoteHead(): ObjectId? = repository.resolve(trackingRef())

    private fun localRef(): String = Constants.R_HEADS + bootstrap.branch

    private fun remoteBranchRef(): String = Constants.R_HEADS + bootstrap.branch

    private fun trackingRef(): String = Constants.R_REMOTES + "$REMOTE/${bootstrap.branch}"

    private class CommitConfigurationReader(
        private val repository: Repository,
        private val commit: RevCommit,
    ) : ConfigurationReader {
        override fun read(path: String): String? {
            val walk = TreeWalk.forPath(repository, path, commit.tree) ?: return null
            walk.use {
                val mode = it.getFileMode(0)
                require(mode == FileMode.REGULAR_FILE || mode == FileMode.EXECUTABLE_FILE) {
                    "Configuration path $path is not a regular file."
                }
                val loader = repository.open(it.getObjectId(0), Constants.OBJ_BLOB)
                require(loader.size <= EvaConfigurationCodec.MAX_FILE_BYTES) { "Configuration file is too large." }
                return loader.bytes.toString(Charsets.UTF_8)
            }
        }
    }

    private companion object {
        const val REMOTE = "origin"
        const val COMMIT_MESSAGE = "Update EVA configuration"
        const val DISABLED_HOOKS = "eva-disabled-hooks"
        const val MAX_TREE_ENTRIES = 4096
        const val MAX_CHECKOUT_BYTES = 64L * 1024L * 1024L
        const val LFS_POINTER = "version https://git-lfs.github.com/spec/v1"
    }

    private enum class FetchState {
        PRESENT,
        ABSENT,
        DELETED,
    }
}

fun validateGitBootstrap(
    value: GitBootstrap,
    allowLocalTransportForTests: Boolean = false,
) {
    val remote = runCatching { URI(value.remoteUrl) }.getOrElse { throw IllegalArgumentException("Enter a valid HTTPS Git remote URL.") }
    val localTestRemote = allowLocalTransportForTests && remote.scheme.equals("file", ignoreCase = true)
    require(localTestRemote || (remote.scheme.equals("https", ignoreCase = true) && !remote.host.isNullOrBlank())) {
        "Git remote must use HTTPS."
    }
    require(remote.userInfo == null && remote.fragment == null && remote.query == null) {
        "Git remote URL cannot contain credentials, a query, or a fragment."
    }
    require(value.remoteUrl.length <= 2048) { "Git remote URL is too long." }
    require(value.branch.length <= 240 && Repository.isValidRefName(Constants.R_HEADS + value.branch) && !value.branch.startsWith('-')) {
        "Enter a valid branch name."
    }
    require(value.authorName.length <= 256 && value.authorName.isNotBlank() && '\n' !in value.authorName && '\r' !in value.authorName) {
        "Enter a commit author name."
    }
    require(
        value.authorEmail.length <= 320 &&
            value.authorEmail.count { it == '@' } == 1 &&
            value.authorEmail.none { it.isWhitespace() || it == '<' || it == '>' },
    ) { "Enter a valid commit author email." }
    require(value.username.length <= 256 && '\n' !in value.username && '\r' !in value.username) {
        "Git username is invalid."
    }
}

internal fun safeGitError(
    failure: Throwable,
    secret: String?,
): String {
    val raw = generateSequence(failure) { it.cause }.mapNotNull { it.message }.firstOrNull().orEmpty()
    val redacted =
        secret?.takeIf { it.isNotEmpty() }?.let { value ->
            sequenceOf(value, URLEncoder.encode(value, Charsets.UTF_8.name()), percentEncode(value))
                .flatMap { encoded -> sequenceOf(encoded, encoded.lowercase()) }
                .distinct()
                .fold(raw) { message, encoded -> message.replace(encoded, "[redacted]", ignoreCase = true) }
        } ?: raw
    return redacted.takeIf { it.isNotBlank() } ?: "Git operation failed."
}

private fun percentEncode(value: String): String =
    value.toByteArray(Charsets.UTF_8).joinToString("") { byte ->
        val unsigned = byte.toInt() and 0xff
        val character = unsigned.toChar()
        if (character.isLetterOrDigit() || character in "-._~") character.toString() else "%%%02X".format(unsigned)
    }
