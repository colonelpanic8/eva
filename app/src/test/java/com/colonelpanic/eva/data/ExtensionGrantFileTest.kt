package com.colonelpanic.eva.data

import android.app.Application
import com.colonelpanic.eva.capability.extensions.ExtensionGrants
import com.colonelpanic.eva.capability.extensions.ExtensionProtocol
import com.colonelpanic.eva.capability.extensions.extensionCapability
import com.colonelpanic.eva.capability.extensions.extensionDescription
import com.colonelpanic.eva.capability.extensions.extensionIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class ExtensionGrantFileTest {
    @Test
    fun `grant and revocation survive file reopen without backup or external storage`() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val descriptor = ExtensionProtocol.describe(extensionDescription).descriptor!!
            val grants = ExtensionGrants(ExtensionGrantFile(context))
            grants.enable(extensionIdentity, descriptor, true)
            val restored = ExtensionGrants(ExtensionGrantFile(context))
            restored.load()
            assertTrue(restored.allowed(extensionIdentity, descriptor, extensionCapability))
            restored.remove(extensionIdentity.packageName)
            val revoked = ExtensionGrants(ExtensionGrantFile(context))
            revoked.load()
            assertFalse(revoked.allowed(extensionIdentity, descriptor, extensionCapability))
            File(context.noBackupFilesDir, "extension-grants.json").writeText("{corrupted}")
            val corrupted = ExtensionGrants(ExtensionGrantFile(context))
            corrupted.load()
            assertFalse(corrupted.allowed(extensionIdentity, descriptor, extensionCapability))
        }
}
