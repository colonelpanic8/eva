package com.colonelpanic.eva.adapters.declarative

import java.io.File

fun contentFixture(name: String = "paseo-content"): PackageDefinition =
    PackageCodec.decode(
        generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .map { File(it, "docs/examples/$name.json") }
            .first { it.isFile }
            .readText(),
    )
