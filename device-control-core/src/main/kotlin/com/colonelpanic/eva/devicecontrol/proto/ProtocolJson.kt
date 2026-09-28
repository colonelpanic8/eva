package com.colonelpanic.eva.devicecontrol.proto

import kotlinx.serialization.json.Json

val ProtocolJson =
    Json {
        classDiscriminator = "kind"
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = true
    }
