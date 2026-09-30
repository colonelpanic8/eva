package com.colonelpanic.eva.capability.extensions

import com.colonelpanic.eva.capability.BoundedJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/** Stable installation identity and adapter-authenticated authority; neither comes from model arguments. */
interface AdapterIdentity {
    val instanceId: String
    val key: String
}

data class PackageIdentity(
    val id: String,
) : AdapterIdentity {
    init {
        require(UUID.fromString(id).toString() == id)
    }

    override val instanceId: String get() = "package:$id"
    override val key: String get() = BoundedJson.digest(JsonArray(listOf(JsonPrimitive(instanceId))))
}
