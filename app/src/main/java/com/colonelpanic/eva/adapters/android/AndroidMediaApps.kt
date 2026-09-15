package com.colonelpanic.eva.adapters.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import java.security.MessageDigest

/**
 * Finds media apps by what they register for, so nothing here names an app. The three signals
 * are the ones EVA already queries to play: a media browser service, Media3 library search, and
 * the play-from-search intent. The last is what catches an app like YouTube that declares
 * neither service but still takes a spoken request.
 */
class AndroidMediaApps(
    context: Context,
    private val launcher: MediaLauncher,
    private val library: MediaLibraryQueueClient,
) : MediaAppSource {
    private val app = context.applicationContext

    override fun scan(): List<DiscoveredMediaApp> {
        val manager = app.packageManager
        val browsers = launcher.browsableApps().associateBy { it.packageName }
        val libraries = library.apps().associateBy { it.packageName }
        val searchers =
            manager
                .queryIntentActivities(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH), 0)
                .mapNotNull { it.activityInfo?.packageName }
                .toSet()
        return (browsers.keys + libraries.keys + searchers)
            .filter { it != app.packageName }
            .sorted()
            .mapNotNull { packageName ->
                val identity = runCatching { identity(manager, packageName) }.getOrNull() ?: return@mapNotNull null
                DiscoveredMediaApp(
                    identity,
                    label(manager, packageName) ?: browsers[packageName]?.label ?: libraries[packageName]?.label ?: packageName,
                    browsers[packageName],
                    libraries[packageName],
                    packageName in searchers,
                )
            }
    }

    private fun label(
        manager: PackageManager,
        packageName: String,
    ): String? =
        runCatching { manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString().trim() }
            .getOrNull()
            ?.takeIf(String::isNotBlank)

    /** The same identity an installed extension gets, minus a component: a media app has no service EVA binds. */
    @Suppress("DEPRECATION")
    private fun identity(
        manager: PackageManager,
        packageName: String,
    ): MediaIdentity {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = manager.getPackageInfo(packageName, flags)
        val uid = checkNotNull(info.applicationInfo).uid
        require(manager.getPackagesForUid(uid)?.toSet() == setOf(packageName)) { "Shared UID is not supported" }
        val certificates = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        require(!certificates.isNullOrEmpty())
        val signer =
            certificates
                .map { signature ->
                    MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
                }.sorted()
                .joinToString(":")
        return MediaIdentity(uid / 100_000, packageName, signer, info.firstInstallTime)
    }
}
