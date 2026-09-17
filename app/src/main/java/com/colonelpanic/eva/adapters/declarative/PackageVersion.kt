package com.colonelpanic.eva.adapters.declarative

/** Orders the three-part package versions the codec accepts; both installs and update checks read it. */
object PackageVersion {
    fun newer(
        next: String,
        old: String,
    ): Boolean {
        val a = next.split('.').map(String::toLong)
        val b = old.split('.').map(String::toLong)
        return a.zip(b).firstOrNull { it.first != it.second }?.let { it.first > it.second } ?: false
    }
}
