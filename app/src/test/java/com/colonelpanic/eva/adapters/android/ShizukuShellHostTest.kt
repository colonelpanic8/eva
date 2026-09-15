package com.colonelpanic.eva.adapters.android

import android.app.Application
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Bundle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import rikka.shizuku.Shizuku

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class, shadows = [GrantedShizuku::class])
class ShizukuShellHostTest {
    @Test
    fun `a granted device action executes without an activity and revoked access refuses it`() =
        runBlocking {
            val app = RuntimeEnvironment.getApplication()
            shadowOf(app.packageManager).installPackage(
                PackageInfo().apply {
                    packageName = "moe.shizuku.privileged.api"
                    applicationInfo = ApplicationInfo().apply { packageName = "moe.shizuku.privileged.api" }
                },
            )
            GrantedShizuku.permission = PackageManager.PERMISSION_GRANTED
            val host = ShizukuShellHost(app)
            assertEquals(null, host.unavailableReason())
            assertEquals("executed", host.run(listOf("app_function", "execute-app-function"), 1000).stdout)
            GrantedShizuku.permission = PackageManager.PERMISSION_DENIED
            assertEquals(ShizukuShellHost.SURFACE_REQUIRED, host.unavailableReason())
            val error = runCatching { host.run(listOf("app_function", "execute-app-function"), 1000) }.exceptionOrNull()
            assertEquals(ShizukuShellHost.SURFACE_REQUIRED, error?.message)
        }
}

@Implements(Shizuku::class)
class GrantedShizuku {
    companion object {
        var permission = PackageManager.PERMISSION_GRANTED

        @Implementation
        @JvmStatic
        fun pingBinder(): Boolean = true

        @Implementation
        @JvmStatic
        fun checkSelfPermission(): Int = permission

        @Implementation
        @JvmStatic
        fun addRequestPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) = Unit

        @Implementation
        @JvmStatic
        fun addBinderReceivedListenerSticky(listener: Shizuku.OnBinderReceivedListener) = Unit

        @Implementation
        @JvmStatic
        fun addBinderDeadListener(listener: Shizuku.OnBinderDeadListener) = Unit

        @Implementation
        @JvmStatic
        fun bindUserService(
            args: Shizuku.UserServiceArgs,
            connection: ServiceConnection,
        ) {
            connection.onServiceConnected(
                ComponentName("test", "test.Shell"),
                object : IAppFunctionsShell.Stub() {
                    override fun execute(
                        args: Array<out String>,
                        timeoutMillis: Long,
                    ): Bundle =
                        Bundle().apply {
                            putString(ShizukuShellHost.STDOUT, "executed")
                            putInt(ShizukuShellHost.EXIT_CODE, 0)
                        }

                    override fun destroy() = Unit
                },
            )
        }
    }
}
