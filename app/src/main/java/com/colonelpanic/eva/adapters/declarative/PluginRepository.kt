package com.colonelpanic.eva.adapters.declarative

import com.colonelpanic.eva.capability.BoundedJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

data class PluginListing(
    val id: String,
    val version: String,
    val title: String,
    val url: String,
    val sha256: String,
    val androidPackages: List<String>,
)

data class PluginPreview(
    val source: String,
    val url: String,
    val json: String,
    val definition: PackageDefinition,
)

class PluginRepository(
    private val fetch: (String, Int) -> ByteArray,
) {
    fun list(source: String): List<PluginListing> {
        val base = repositoryUrl(source)
        val root = BoundedJson.parse(fetch(base.toString(), MAX_INDEX).toString(Charsets.UTF_8), MAX_INDEX).jsonObject
        require(
            root.keys == setOf("formatVersion", "packages") && root["formatVersion"] == JsonPrimitive(1),
        ) { "Unsupported repository index" }
        val entries = root["packages"] as? JsonArray ?: error("Expected package listings")
        require(entries.size <= 1000)
        val result =
            entries.map { value ->
                val item = value.jsonObject
                require(item.keys == setOf("id", "version", "title", "url", "sha256", "androidPackages"))
                val resolved = requireNotNull(base.resolve(text(item, "url", 2048)))
                val url = repositoryUrl(resolved.toString())
                require(
                    url.scheme == base.scheme && url.host == base.host && url.port == base.port,
                ) { "Package files must stay on the index origin" }
                val digest = text(item, "sha256", 64)
                require(digest.length == 64 && digest.all { it in '0'..'9' || it in 'a'..'f' })
                val apps = item["androidPackages"] as? JsonArray ?: error("Expected Android package IDs")
                require(apps.size <= 16)
                PluginListing(
                    text(item, "id", 128),
                    text(item, "version", 40),
                    text(item, "title", 120),
                    url.toString(),
                    digest,
                    apps.map {
                        (it as JsonPrimitive)
                            .also { p ->
                                require(p.isString)
                            }.content
                            .also { app -> require(app.length in 1..200) }
                    },
                )
            }
        require(result.map { it.id }.distinct().size == result.size) { "Duplicate extension ID in index" }
        return result
    }

    fun previewUrl(source: String): PluginPreview {
        val url = repositoryUrl(source).toString()
        val bytes = fetch(url, PackageCodec.MAX_BYTES)
        require(bytes.size <= PackageCodec.MAX_BYTES)
        val json = bytes.toString(Charsets.UTF_8)
        return PluginPreview(url, url, json, PackageCodec.decode(json))
    }

    fun preview(
        source: String,
        listing: PluginListing,
    ): PluginPreview {
        val bytes = fetch(listing.url, PackageCodec.MAX_BYTES)
        require(bytes.size <= PackageCodec.MAX_BYTES)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(digest == listing.sha256) { "Extension file changed since the index was fetched. Refresh the repository." }
        val json = bytes.toString(Charsets.UTF_8)
        val definition = PackageCodec.decode(json)
        require(
            definition.id == listing.id && definition.version == listing.version && definition.androidPackages == listing.androidPackages,
        ) {
            "Extension identity, version or app targets disagree with its index"
        }
        return PluginPreview(repositoryUrl(source).toString(), listing.url, json, definition)
    }

    companion object {
        const val MAX_INDEX = 1_048_576

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

        fun installationSource(value: String): String {
            if (value.startsWith("file-import:")) {
                val id = value.removePrefix("file-import:")
                require(UUID.fromString(id).toString() == id)
                return value
            }
            return repositoryUrl(value).toString()
        }

        fun repositoryUrl(value: String): HttpUrl =
            value.trim().toHttpUrl().also {
                require(it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.fragment == null) {
                    "Use an HTTPS repository URL without credentials or fragments"
                }
            }

        private fun text(
            root: JsonObject,
            key: String,
            max: Int,
        ): String =
            (root[key] as? JsonPrimitive)?.also { require(it.isString) }?.content?.also {
                require(it.length in 1..max && it.none(Char::isISOControl))
            } ?: error("Missing $key")
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
        val request = Request.Builder().url(PluginRepository.repositoryUrl(url)).build()
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
