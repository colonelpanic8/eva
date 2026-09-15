package com.colonelpanic.eva.adapters.declarative

import com.colonelpanic.eva.capability.BoundedJson
import com.colonelpanic.eva.capability.extensions.PackageIdentity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

data class InstalledPlugin(
    val identity: PackageIdentity,
    val source: String,
    val url: String,
    val definition: PackageDefinition,
    val json: String,
)

class PluginInstallations(
    read: () -> String?,
    private val write: (String) -> Unit,
) {
    private var entries =
        read()
            ?.let { encoded ->
                BoundedJson
                    .parse(encoded, MAX_BYTES)
                    .jsonArray
                    .map { item ->
                        val root = item.jsonObject
                        val json = root.getValue("json").jsonPrimitive.content
                        InstalledPlugin(
                            PackageIdentity(root.getValue("instance").jsonPrimitive.content),
                            root.getValue("source").jsonPrimitive.content,
                            root.getValue("url").jsonPrimitive.content,
                            PackageCodec.decode(json),
                            json,
                        )
                    }.also { values ->
                        require(values.map { it.identity }.distinct().size == values.size)
                        require(values.map { it.source to it.definition.id }.distinct().size == values.size)
                    }
            }.orEmpty()

    @Synchronized fun all(): List<InstalledPlugin> = entries.toList()

    @Synchronized fun install(preview: PluginPreview): InstalledPlugin {
        val definition = PackageCodec.decode(preview.json)
        require(definition.digest == preview.definition.digest) { "Preview changed; review it again" }
        val source = PluginRepository.installationSource(preview.source)
        if (source.startsWith("file-import:")) require(preview.url == source)
        val old = entries.find { it.source == source && it.definition.id == definition.id }
        if (old != null && old.definition.digest != definition.digest) {
            require(
                newer(definition.version, old.definition.version),
            ) { "Changed packages must increase their version; downgrades are refused" }
        }
        val installed =
            InstalledPlugin(
                old?.identity ?: PackageIdentity(UUID.randomUUID().toString()),
                source,
                PluginRepository.installationSource(preview.url),
                definition,
                preview.json,
            )
        commit(entries.filterNot { it.identity == installed.identity } + installed)
        return installed
    }

    @Synchronized fun remove(instance: String) = commit(entries.filterNot { it.identity.id == instance })

    @Synchronized fun restore(restored: List<InstalledPlugin>) {
        validate(restored)
        commit(restored)
    }

    private fun commit(next: List<InstalledPlugin>) {
        val encoded = encode(next)
        write(encoded)
        entries = next
    }

    companion object {
        const val MAX_BYTES = 4_194_304

        fun validate(entries: List<InstalledPlugin>) {
            entries.forEach { item -> require(PackageCodec.decode(item.json).digest == item.definition.digest) }
            require(entries.map { it.identity }.distinct().size == entries.size)
            require(entries.map { it.source to it.definition.id }.distinct().size == entries.size)
            encode(entries)
        }

        private fun encode(next: List<InstalledPlugin>): String {
            require(next.size <= 64) { "At most 64 repository extensions may be installed" }
            val encoded =
                JsonArray(
                    next.map { item ->
                        JsonObject(
                            mapOf(
                                "instance" to JsonPrimitive(item.identity.id),
                                "source" to JsonPrimitive(item.source),
                                "url" to JsonPrimitive(item.url),
                                "json" to JsonPrimitive(item.json),
                            ),
                        )
                    },
                ).toString()
            require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Installed extensions exceed the storage limit" }
            return encoded
        }
    }

    private fun newer(
        next: String,
        old: String,
    ): Boolean {
        val a = next.split('.').map(String::toLong)
        val b = old.split('.').map(String::toLong)
        return a.zip(b).firstOrNull { it.first != it.second }?.let { it.first > it.second } ?: false
    }
}
