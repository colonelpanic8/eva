package com.colonelpanic.eva.adapters.declarative

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.TagOpt
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

data class PluginListing(
    val id: String,
    val version: String,
    val title: String,
    /** Path inside the catalog, `packages/<name>.json`. */
    val url: String,
    val androidPackages: List<String>,
    val revision: String? = null,
)

data class PluginPreview(
    val source: String,
    val url: String,
    val json: String,
    val definition: PackageDefinition,
    val revision: String? = null,
)

data class CatalogListing(
    val listings: List<PluginListing>,
    /** Files under `packages/` that did not decode, so one bad file cannot hide the rest. */
    val problems: List<String>,
    val revision: String? = null,
)

/**
 * A package catalog is a Git repository whose `packages/` directory holds one JSON file per package.
 * EVA keeps a read-only clone per source and fast-forwards it to the remote head on refresh.
 */
class PluginRepository(
    private val checkouts: File,
    private val fetch: (String, Int) -> ByteArray,
    private val allowLocalTransportForTests: Boolean = false,
) {
    fun source(value: String): String = catalogSource(value, allowLocalTransportForTests)

    @Synchronized
    fun list(
        source: String,
        revision: String? = null,
    ): CatalogListing {
        val remote = source(source)
        val directory = checkout(remote)
        if (revision == null) synchronize(directory, remote) else ensureRevision(remote, revision)
        return Git.open(directory).use { git ->
            val commit = revision ?: requireNotNull(git.repository.resolve(Constants.HEAD)).name
            RevWalk(git.repository).use { walk ->
                val tree = walk.parseCommit(ObjectId.fromString(commit)).tree
                val problems = mutableListOf<String>()
                val listings = mutableListOf<PluginListing>()
                TreeWalk(git.repository).use { files ->
                    files.addTree(tree)
                    files.isRecursive = true
                    while (files.next()) {
                        val path = files.pathString
                        if (!PACKAGE_PATH.matches(path)) continue
                        runCatching {
                            val preview = readRevision(remote, path, commit)
                            val definition = preview.definition
                            PluginListing(definition.id, definition.version, definition.title, path, definition.androidPackages, commit)
                        }.onSuccess(listings::add).onFailure {
                            problems +=
                                "${path.substringAfterLast('/')}: ${it.message ?: "could not be read"}"
                        }
                    }
                }
                require(listings.map { it.id }.distinct().size == listings.size) { "The catalog declares one package ID twice" }
                CatalogListing(listings.sortedBy { it.url }, problems, commit)
            }
        }
    }

    @Synchronized
    fun pinned(
        source: String,
        path: String,
        revision: String,
    ): PluginPreview {
        val remote = source(source)
        validateReference(path, revision)
        ensureRevision(remote, revision)
        return readRevision(remote, path, revision)
    }

    private fun ensureRevision(
        remote: String,
        revision: String,
    ) {
        require(REVISION.matches(revision)) { "Extension revisions must be full Git commit IDs." }
        val directory = checkout(remote)
        if (!File(directory, Constants.DOT_GIT).isDirectory) synchronize(directory, remote)
        Git.open(directory).use { git ->
            if (!git.repository.objectDatabase.has(ObjectId.fromString(revision))) {
                git
                    .fetch()
                    .setRemote(REMOTE)
                    .setRefSpecs(RefSpec(revision))
                    .setTagOpt(TagOpt.NO_TAGS)
                    .setTimeout(TIMEOUT_SECONDS)
                    .call()
            }
            RevWalk(git.repository).use { it.parseCommit(ObjectId.fromString(revision)) }
        }
    }

    private fun readRevision(
        remote: String,
        path: String,
        revision: String,
    ): PluginPreview =
        Git.open(checkout(remote)).use { git ->
            RevWalk(git.repository).use { walk ->
                val commit = walk.parseCommit(ObjectId.fromString(revision))
                val file =
                    requireNotNull(TreeWalk.forPath(git.repository, path, commit.tree)) {
                        "Extension $path does not exist in $remote at $revision."
                    }
                file.use {
                    require(file.getFileMode(0) in setOf(FileMode.REGULAR_FILE, FileMode.EXECUTABLE_FILE)) {
                        "Extension $path must be a regular file."
                    }
                    val json =
                        git.repository
                            .open(file.getObjectId(0))
                            .getBytes(PackageCodec.MAX_BYTES)
                            .toString(Charsets.UTF_8)
                    PluginPreview(remote, path, json, PackageCodec.decode(json), revision)
                }
            }
        }

    fun previewUrl(source: String): PluginPreview {
        val url =
            source
                .trim()
                .toHttpUrl()
                .toString()
                .also { catalogSource(it) }
        val bytes = fetch(url, PackageCodec.MAX_BYTES)
        require(bytes.size <= PackageCodec.MAX_BYTES)
        val json = bytes.toString(Charsets.UTF_8)
        return PluginPreview(url, url, json, PackageCodec.decode(json))
    }

    @Synchronized
    fun preview(
        source: String,
        listing: PluginListing,
    ): PluginPreview {
        val remote = source(source)
        val revision = listing.revision ?: Git.open(checkout(remote)).use { requireNotNull(it.repository.resolve(Constants.HEAD)).name }
        val preview = pinned(remote, listing.url, revision)
        require(preview.definition.id == listing.id && preview.definition.version == listing.version) {
            "Extension changed since the catalog was listed. Refresh the repository."
        }
        return preview
    }

    private fun checkout(remote: String): File =
        File(
            checkouts,
            MessageDigest
                .getInstance("SHA-256")
                .digest(remote.toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(32),
        )

    private fun synchronize(
        checkout: File,
        remote: String,
    ) {
        if (!File(checkout, Constants.DOT_GIT).isDirectory) {
            checkout.deleteRecursively()
            checkouts.mkdirs()
            try {
                Git
                    .cloneRepository()
                    .setURI(remote)
                    .setDirectory(checkout)
                    .setCloneAllBranches(false)
                    .setNoTags()
                    .setTimeout(TIMEOUT_SECONDS)
                    .call()
                    .close()
            } catch (failure: Exception) {
                checkout.deleteRecursively()
                throw failure
            }
            return
        }
        Git.open(checkout).use { git ->
            val branch = requireNotNull(git.repository.branch) { "The catalog clone has no branch; remove and refresh it." }
            val tracking = "${Constants.R_REMOTES}$REMOTE/$branch"
            git
                .fetch()
                .setRemote(REMOTE)
                .setRefSpecs(RefSpec("+${Constants.R_HEADS}$branch:$tracking"))
                .setTagOpt(TagOpt.NO_TAGS)
                .setTimeout(TIMEOUT_SECONDS)
                .call()
            val head = requireNotNull(git.repository.resolve(tracking)) { "The catalog's $branch branch is gone from its remote." }
            git
                .reset()
                .setMode(ResetCommand.ResetType.HARD)
                .setRef(head.name)
                .call()
        }
    }

    companion object {
        const val PACKAGES = "packages"
        private val REVISION = Regex("[0-9a-f]{40}")

        fun validateReference(
            path: String,
            revision: String,
        ) {
            require(PACKAGE_PATH.matches(path)) { "Catalog packages live under packages/ and must name a JSON file." }
            require(REVISION.matches(revision)) { "Extension revisions must be full Git commit IDs." }
        }

        private const val REMOTE = Constants.DEFAULT_REMOTE_NAME
        private const val TIMEOUT_SECONDS = 20
        private val PACKAGE_PATH = Regex("$PACKAGES/[A-Za-z0-9._-]{1,200}\\.json")
        private val LEGACY_INDEX = Regex("https://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/[^/]+/index\\.json")
        private val LEGACY_PACKAGE = Regex("https://raw\\.githubusercontent\\.com/[^/]+/[^/]+/[^/]+/($PACKAGES/[^/]+\\.json)")

        fun filePreview(input: InputStream): PluginPreview {
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            input.use {
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    require(bytes.size() + count <= PackageCodec.MAX_BYTES) { "Extension file is too large" }
                    bytes.write(buffer, 0, count)
                }
            }
            val json = bytes.toString(Charsets.UTF_8.name())
            val source = "file-import:${UUID.randomUUID()}"
            return PluginPreview(source, source, json, PackageCodec.decode(json))
        }

        /** The stored identity of where a package came from; network use is validated separately by [source]. */
        fun installationSource(value: String): String {
            if (value.startsWith("file-import:")) {
                val id = value.removePrefix("file-import:")
                require(UUID.fromString(id).toString() == id)
                return value
            }
            return catalogSource(value, allowLocal = true)
        }

        fun installationUrl(value: String): String =
            when {
                value.startsWith("file-import:") -> installationSource(value)
                PACKAGE_PATH.matches(value) -> value
                else -> installationSource(value)
            }

        /** Rewrites an index-era source so an existing installation keeps matching its catalog. */
        fun legacySource(value: String): String =
            LEGACY_INDEX.matchEntire(value.trim())?.let { "https://github.com/${it.groupValues[1]}/${it.groupValues[2]}.git" }
                ?: value.trim()

        fun legacyUrl(value: String): String = LEGACY_PACKAGE.matchEntire(value.trim())?.groupValues?.get(1) ?: value.trim()

        fun catalogSource(
            value: String,
            allowLocal: Boolean = false,
        ): String {
            val remote = legacySource(value)
            val uri = runCatching { URI(remote) }.getOrElse { throw IllegalArgumentException("Enter a valid HTTPS Git repository URL") }
            val local = allowLocal && uri.scheme.equals("file", ignoreCase = true)
            require(local || (uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank())) {
                "Use an HTTPS Git repository URL"
            }
            require(uri.userInfo == null && uri.query == null && uri.fragment == null && remote.length <= 2048) {
                "Repository URLs cannot contain credentials, a query, or a fragment"
            }
            return remote.trimEnd('/')
        }
    }
}

class RepositoryHttpClient {
    private val client =
        OkHttpClient
            .Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()

    fun fetch(
        url: String,
        maxBytes: Int,
    ): ByteArray {
        val request = Request.Builder().url(PluginRepository.catalogSource(url).toHttpUrl()).build()
        return client.newCall(request).execute().use { response ->
            require(response.code == 200) { "Repository returned HTTP ${response.code}; use a raw HTTPS file URL" }
            require(response.body.contentLength() <= maxBytes) { "Repository file is too large" }
            response.body.byteStream().use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(out.size() + count <= maxBytes) { "Repository file is too large" }
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
        }
    }
}
