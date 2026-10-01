package com.colonelpanic.eva.capability.extensions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

val extensionIdentity = ExtensionIdentity(0, "example.app", "example.app/.Extension", "abc", 10001, 1)
val extensionSchema =
    Json
        .parseToJsonElement(
            """{"type":"object","properties":{},"required":[],"additionalProperties":false}""",
        ).jsonObject
val extensionCapability = Capability("read", "Read", "Read data", extensionSchema, Effect.READ, 1000, 16384)
val extensionDescription =
    """
    {
    "protocolVersion":1,"status":"completed","reasonCode":null,"truncated":false,"content":[],
    "descriptor":{"protocolVersion":1,"descriptorRevision":"v1","authorizationScopeRevision":"account1",
    "title":"Example","capabilities":[{
    "tool":{"name":"read","title":"Read","description":"Read data","inputSchema":$extensionSchema},"effects":"read",
    "execution":{"mode":"synchronous","requiresForeground":false,"maxWaitMillis":1000},
    "result":{"maxBytes":16384}}]}}
    """.trimIndent()
