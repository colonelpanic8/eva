package com.colonelpanic.eva.adapters.declarative

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.TagOpt
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
)

data class PluginPreview(
    val source: String,
    val url: String,
    val json: String,
    val definition: PackageDefinition,
)

data class CatalogListing(
    val listings: List<PluginListing>,
    /** Files under `packages/` that did not decode, so one bad file cannot hide the rest. */
    val problems: List<String>,
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

    fun list(source: String): CatalogListing {
        val remote = source(source)
        val checkout = checkout(remote)
        synchronize(checkout, remote)
        val files =
            File(checkout, PACKAGES)
                .listFiles { file -> file.isFile && PACKAGE_FILE.matches(file.name) }
                .orEmpty()
                .sortedBy { it.name }
        val problems = mutableListOf<String>()
        val listings =
            files.mapNotNull { file ->
                runCatching {
                    val definition = PackageCodec.decode(read(file))
                    PluginListing(definition.id, definition.version, definition.title, "$PACKAGES/${file.name}", definition.androidPackages)
                }.getOrElse { failure ->
                    problems += "${file.name}: ${failure.message ?: "could not be read"}"
                    null
                }
            }
        require(listings.map { it.id }.distinct().size == listings.size) { "The catalog declares one package ID twice" }
        return CatalogListing(listings, problems)
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

    fun preview(
        source: String,
        listing: PluginListing,
    ): PluginPreview {
        val remote = source(source)
        require(PACKAGE_PATH.matches(listing.url)) { "Catalog packages live under $PACKAGES/" }
        val file = File(checkout(remote), listing.url)
        require(file.isFile) { "Extension file is no longer in the catalog. Refresh the repository." }
        val json = read(file)
        val definition = PackageCodec.decode(json)
        require(definition.id == listing.id && definition.version == listing.version) {
            "Extension changed since the catalog was listed. Refresh the repository."
        }
        return PluginPreview(remote, listing.url, json, definition)
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

    private fun read(file: File): String {
        require(file.length() <= PackageCodec.MAX_BYTES) { "Extension file is too large" }
        return file.readText()
    }

    companion object {
        const val PACKAGES = "packages"
        private const val REMOTE = Constants.DEFAULT_REMOTE_NAME
        private const val TIMEOUT_SECONDS = 20
        private val PACKAGE_FILE = Regex("[A-Za-z0-9._-]{1,200}\\.json")
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
