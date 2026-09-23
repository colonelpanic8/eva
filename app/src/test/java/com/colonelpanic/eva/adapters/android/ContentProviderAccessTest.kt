package com.colonelpanic.eva.adapters.android

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.content.pm.ProviderInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ContentProviderAccessTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val authority = "com.colonelpanic.mova.provider"
    private val permission = ContentProviderAccess.MOVA_READ_TODOS

    @Test
    fun `Mova requires declared dangerous runtime permission and rechecks denial and revocation`() {
        register(permission)
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
        info.requestedPermissions = (info.requestedPermissions.orEmpty().toList() + permission).toTypedArray()
        shadowOf(app.packageManager).installPackage(info)
        shadowOf(app.packageManager).addPermissionInfo(
            PermissionInfo().apply {
                name = permission
                packageName = "com.colonelpanic.mova"
                protectionLevel = PermissionInfo.PROTECTION_DANGEROUS
            },
        )
        val denied = ContentProviderAccess.inspect(app, authority)
        assertTrue(denied.canRequest)
        assertTrue(denied.problem!!.contains(permission))
        assertEquals(permission, ContentProviderAccess.requiredPermission(app, authority))
        shadowOf(app).grantPermissions(permission)
        assertNull(ContentProviderAccess.inspect(app, authority).problem)
        shadowOf(app).denyPermissions(permission)
        assertTrue(ContentProviderAccess.inspect(app, authority).canRequest)
        info.requestedPermissions = emptyArray()
        shadowOf(app.packageManager).installPackage(info)
        assertFalse(ContentProviderAccess.inspect(app, authority).canRequest)
    }

    @Test
    fun `permissionless providers require no permission and arbitrary permissions cannot be requested`() {
        register(null)
        assertNull(ContentProviderAccess.inspect(app, authority).problem)
        assertNull(ContentProviderAccess.requiredPermission(app, authority))
        assertFalse(ContentProviderAccess.inspect(app, authority).canRequest)
        register("private.provider.READ")
        assertFalse(ContentProviderAccess.inspect(app, authority).canRequest)
        assertTrue(ContentProviderAccess.inspect(app, authority).problem!!.contains("cannot request"))
        assertNull(ContentProviderAccess.requiredPermission(app, authority))
        assertTrue(ContentProviderAccess.inspect(app, "missing.provider").problem!!.contains("Install or enable"))
    }

    private fun register(read: String?) {
        shadowOf(app.packageManager).addOrUpdateProvider(
            ProviderInfo().apply {
                authority = this@ContentProviderAccessTest.authority
                packageName = "com.colonelpanic.mova"
                name = "com.colonelpanic.mova.TodosProvider"
                exported = true
                enabled = true
                readPermission = read
                applicationInfo = ApplicationInfo().apply { enabled = true }
            },
        )
    }
}
