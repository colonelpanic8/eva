package com.colonelpanic.eva.capability.extensions

class MemoryGrantPersistence : ExtensionGrantPersistence {
    var json: String? = null
    var fail = false
    var writes = 0

    override suspend fun read() = json

    override suspend fun write(json: String) {
        check(!fail)
        writes++
        this.json = json
    }
}
