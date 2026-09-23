package com.colonelpanic.eva.capability.extensions

import com.colonelpanic.eva.capability.BoundedJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ExtensionGrant(
    val identityKey: String,
    val digest: String,
    val mutations: Set<String> = emptySet(),
)

interface ExtensionGrantPersistence {
    suspend fun read(): String?

    suspend fun write(json: String)
}

/** Mutations are serialized with CapabilityRegistry.changeAuthorization by the runtime. */
class ExtensionGrants(
    private val persistence: ExtensionGrantPersistence,
) {
    @Volatile private var grants = emptyMap<String, ExtensionGrant>()

    @Volatile var storageFailed = false
        private set

    suspend fun load() {
        grants =
            runCatching {
                val json = persistence.read() ?: return@runCatching emptyMap()
                BoundedJson.parse(json, 1_048_576).jsonObject.mapValues { (_, value) ->
                    val entry = value.jsonObject
                    require(entry.keys == setOf("identity", "digest", "mutations"))
                    ExtensionGrant(
                        entry.getValue("identity").jsonPrimitive.content,
                        entry.getValue("digest").jsonPrimitive.content,
                        entry
                            .getValue("mutations")
                            .jsonArray
                            .map { it.jsonPrimitive.content }
                            .toSet(),
                    )
                }
            }.getOrElse {
                storageFailed = true
                emptyMap()
            }
    }

    fun grant(
        identity: AdapterIdentity,
        descriptor: Descriptor,
    ): ExtensionGrant? = grants[identity.instanceId]?.takeIf { it.identityKey == identity.key && it.digest == descriptor.digest }

    fun all(): Map<String, ExtensionGrant> = grants.toMap()

    fun allowed(
        identity: AdapterIdentity,
        descriptor: Descriptor,
        capability: Capability,
    ): Boolean {
        if (storageFailed) return false
        val grant = grant(identity, descriptor) ?: return false
        return capability.effect == Effect.READ || capability.name in grant.mutations
    }

    suspend fun enable(
        identity: AdapterIdentity,
        descriptor: Descriptor,
        enabled: Boolean,
    ) {
        save(
            if (enabled) {
                grants + (identity.instanceId to ExtensionGrant(identity.key, descriptor.digest))
            } else {
                grants -
                    identity.instanceId
            },
        )
    }

    /** Enables the extension and every non-read action at once, as for a shipped default package. */
    suspend fun enableAll(
        identity: AdapterIdentity,
        descriptor: Descriptor,
    ) {
        val mutations =
            descriptor.capabilities
                .filter { it.effect != Effect.READ }
                .map { it.name }
                .toSet()
        save(grants + (identity.instanceId to ExtensionGrant(identity.key, descriptor.digest, mutations)))
    }

    /**
     * Moves an existing grant onto a changed catalog contract. Previously declined actions stay
     * declined; newly named non-read actions are approved when auto-enable is on.
     */
    suspend fun rebind(
        identity: AdapterIdentity,
        descriptor: Descriptor,
        previousDigest: String,
        previousActions: Set<String>,
        enableNewActions: Boolean,
    ): Boolean {
        val current = grants[identity.instanceId]?.takeIf { it.identityKey == identity.key } ?: return false
        if (current.digest == descriptor.digest) return false
        if (current.digest != previousDigest) return false
        val mutations =
            descriptor.capabilities
                .filter { it.effect != Effect.READ && (it.name in current.mutations || (enableNewActions && it.name !in previousActions)) }
                .map { it.name }
                .toSet()
        save(grants + (identity.instanceId to ExtensionGrant(identity.key, descriptor.digest, mutations)))
        return true
    }

    suspend fun mutation(
        identity: AdapterIdentity,
        descriptor: Descriptor,
        name: String,
        enabled: Boolean,
    ) {
        val current = checkNotNull(grant(identity, descriptor)) { "Enable the extension first." }
        require(descriptor.capabilities.any { it.name == name && it.effect != Effect.READ })
        val names = if (enabled) current.mutations + name else current.mutations - name
        save(grants + (identity.instanceId to current.copy(mutations = names)))
    }

    suspend fun remove(packageName: String) {
        save(grants - packageName)
    }

    suspend fun reconcile(installed: List<InstalledExtension>) {
        val packages = installed.associateBy { it.identity?.instanceId }
        save(
            grants.filter { (name, grant) ->
                val entry = packages[name]
                entry?.identity?.key == grant.identityKey && !entry.contractRejected &&
                    (entry.descriptor == null || entry.descriptor.digest == grant.digest)
            },
        )
    }

    suspend fun restore(
        restored: Map<String, ExtensionGrant>,
        installed: List<InstalledExtension>,
    ): Set<String> {
        val available = installed.associateBy { it.identity?.instanceId }
        val accepted =
            restored.filter { (instance, grant) ->
                val entry = available[instance]
                val descriptor = entry?.descriptor
                entry?.identity?.key == grant.identityKey && descriptor?.digest == grant.digest && !entry.contractRejected &&
                    grant.mutations.all { name -> descriptor.capabilities.any { it.name == name && it.effect != Effect.READ } }
            }
        save(accepted)
        return restored.keys - accepted.keys
    }

    private suspend fun save(next: Map<String, ExtensionGrant>) {
        if (next == grants && !storageFailed) return
        val json =
            JsonObject(
                next.mapValues { (_, grant) ->
                    JsonObject(
                        mapOf(
                            "identity" to JsonPrimitive(grant.identityKey),
                            "digest" to JsonPrimitive(grant.digest),
                            "mutations" to JsonArray(grant.mutations.sorted().map(::JsonPrimitive)),
                        ),
                    )
                },
            ).toString()
        try {
            persistence.write(json)
        } catch (error: Exception) {
            storageFailed = true
            throw error
        }
        grants = next
        storageFailed = false
    }
}
