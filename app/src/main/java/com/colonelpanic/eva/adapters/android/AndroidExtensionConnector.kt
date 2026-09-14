package com.colonelpanic.eva.adapters.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import com.colonelpanic.eva.capability.extensions.ExtensionCandidate
import com.colonelpanic.eva.capability.extensions.ExtensionConnection
import com.colonelpanic.eva.capability.extensions.ExtensionConnector
import com.colonelpanic.eva.capability.extensions.ExtensionIdentity
import com.colonelpanic.eva.extension.IEvaExtension
import com.colonelpanic.eva.extension.IEvaExtensionCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resumeWithException

class AndroidExtensionConnector(
    private val context: Context,
) : ExtensionConnector {
    @Suppress("DEPRECATION")
    fun scan(): List<ExtensionCandidate> =
        context.packageManager
            .queryIntentServices(Intent(ACTION), PackageManager.GET_META_DATA or PackageManager.GET_DISABLED_COMPONENTS)
            .mapNotNull { resolved ->
                val service = resolved.serviceInfo ?: return@mapNotNull null
                val component = ComponentName(service.packageName, service.name)
                val identity = runCatching { identity(component) }.getOrNull() ?: return@mapNotNull null
                ExtensionCandidate(
                    identity,
                    service.exported,
                    service.enabled && service.applicationInfo.enabled,
                    service.metaData?.getInt(VERSION, 0) ?: 0,
                )
            }

    @Suppress("DEPRECATION")
    private fun identity(component: ComponentName): ExtensionIdentity {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = pm.getPackageInfo(component.packageName, flags)
        val uid = checkNotNull(info.applicationInfo).uid
        require(pm.getPackagesForUid(uid)?.toSet() == setOf(component.packageName)) { "Shared UID is not supported" }
        val certificates = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        require(!certificates.isNullOrEmpty())
        val signer =
            certificates
                .map { signature ->
                    MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
                }.sorted()
                .joinToString(":")
        return ExtensionIdentity(uid / 100_000, component.packageName, component.flattenToString(), signer, uid, info.firstInstallTime)
    }

    override suspend fun connect(
        identity: ExtensionIdentity,
        died: () -> Unit,
    ): ExtensionConnection {
        val component = checkNotNull(ComponentName.unflattenFromString(identity.component))
        withContext(Dispatchers.IO) { require(identity(component) == identity) { "Extension identity changed" } }
        return suspendCancellableCoroutine { continuation ->
            val closed = AtomicBoolean(false)
            val delivered = AtomicBoolean(false)
            val bindingLock = Any()
            var binder: IBinder? = null
            val death = IBinder.DeathRecipient { died() }
            lateinit var service: ServiceConnection

            fun releaseBinding() =
                synchronized(bindingLock) {
                    if (closed.compareAndSet(false, true)) {
                        runCatching { binder?.unlinkToDeath(death, 0) }
                        runCatching { context.unbindService(service) }
                    }
                }

            fun failed() {
                if (delivered.compareAndSet(false, true)) {
                    continuation.resumeWithException(IllegalStateException("Extension binding failed"))
                } else {
                    died()
                }
            }
            service =
                object : ServiceConnection {
                    override fun onServiceConnected(
                        name: ComponentName,
                        remote: IBinder,
                    ) {
                        try {
                            synchronized(bindingLock) {
                                if (closed.get()) return
                                require(name == component)
                                binder = remote
                                remote.linkToDeath(death, 0)
                            }
                            val api = IEvaExtension.Stub.asInterface(remote)
                            val connection =
                                object : ExtensionConnection {
                                    private fun callback(receive: (Int, String, String) -> Unit) =
                                        object : IEvaExtensionCallback.Stub() {
                                            override fun onResult(
                                                requestId: String?,
                                                responseJson: String?,
                                            ) {
                                                val uid = Binder.getCallingUid()
                                                if (!closed.get() && requestId != null &&
                                                    responseJson != null
                                                ) {
                                                    receive(uid, requestId, responseJson)
                                                }
                                            }
                                        }

                                    override fun describe(
                                        id: String,
                                        deadline: Long,
                                        callback: (Int, String, String) -> Unit,
                                    ) {
                                        require(identity(component) == identity)
                                        api.describe(id, deadline, callback(callback))
                                    }

                                    override fun execute(
                                        id: String,
                                        revision: String,
                                        capability: String,
                                        arguments: String,
                                        deadline: Long,
                                        callback: (Int, String, String) -> Unit,
                                    ) {
                                        require(identity(component) == identity)
                                        api.execute(id, revision, capability, arguments, deadline, callback(callback))
                                    }

                                    override fun close() = releaseBinding()
                                }
                            if (delivered.compareAndSet(false, true)) continuation.resume(connection) { _, _, _ -> releaseBinding() }
                        } catch (_: Exception) {
                            failed()
                            releaseBinding()
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName) = failed()

                    override fun onBindingDied(name: ComponentName) = failed()

                    override fun onNullBinding(name: ComponentName) = failed()
                }
            try {
                if (!context.bindService(Intent(ACTION).setComponent(component), service, Context.BIND_AUTO_CREATE)) {
                    failed()
                    releaseBinding()
                }
            } catch (_: Exception) {
                failed()
                releaseBinding()
            }
            continuation.invokeOnCancellation { releaseBinding() }
        }
    }

    companion object {
        const val ACTION = "com.colonelpanic.eva.action.EXTENSION"
        const val VERSION = "com.colonelpanic.eva.extension.version"
    }
}
