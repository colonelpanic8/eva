package com.colonelpanic.eva.ui.about

/** The installed package's own identity, read at runtime rather than compiled in. */
data class AboutInfo(
    val version: String? = null,
    val versionCode: Long? = null,
    val applicationId: String = "",
) {
    /** The pair a bug report needs, or a plain admission when the package could not be read. */
    val versionLabel: String
        get() =
            when {
                version == null -> "Unavailable"
                versionCode == null -> version
                else -> "$version ($versionCode)"
            }
}
