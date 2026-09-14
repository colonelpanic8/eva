package com.colonelpanic.eva.adapters.declarative

import android.content.Intent
import com.colonelpanic.eva.adapters.android.AndroidDeclarativeHost
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class, manifest = Config.NONE)
class AndroidPackageIntentTest {
    @Test
    fun `Android intent retains encoded URI and scalar extra types without injected flags or components`() {
        val intent =
            AndroidDeclarativeHost.buildIntent(
                IntentRequest(
                    Intent.ACTION_VIEW,
                    "mova://create?title=Rent%20%26%20bills",
                    mapOf("title" to JsonPrimitive("Rent & bills"), "count" to JsonPrimitive(3), "enabled" to JsonPrimitive(true)),
                    "com.colonelpanic.mova",
                ),
            )
        assertEquals("mova://create?title=Rent%20%26%20bills", intent.dataString)
        assertEquals("com.colonelpanic.mova", intent.`package`)
        assertEquals("Rent & bills", intent.getStringExtra("title"))
        assertEquals(3, intent.getIntExtra("count", -1))
        assertEquals(true, intent.getBooleanExtra("enabled", false))
        assertEquals(0, intent.flags)
        assertNull(intent.component)
    }
}
