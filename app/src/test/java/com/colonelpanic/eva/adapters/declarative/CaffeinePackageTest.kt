package com.colonelpanic.eva.adapters.declarative

import android.content.ActivityNotFoundException
import com.colonelpanic.eva.adapters.android.AndroidDeclarativeHost
import com.colonelpanic.eva.adapters.android.AndroidIntentHost
import com.colonelpanic.eva.capability.InvocationStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class, manifest = Config.NONE)
class CaffeinePackageTest {
    @Test
    fun `app matching uses fixed targets even without optional metadata`() {
        val definition = PackageCodec.decode(source)
        assertEquals(listOf("moe.zhs.caffeine"), definition.copy(androidPackages = emptyList()).appTargets())
        assertEquals(listOf("moe.zhs.caffeine"), definition.appTargets())
    }

    private val source get() =
        generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .map { File(it, "docs/examples/caffeine.json") }
            .first { it.isFile }
            .readText()

    @Test
    fun `example actions pin component and integer status and retain write effects`() {
        val definition = PackageCodec.decode(source)
        assertEquals(listOf("enable", "disable"), definition.capabilities.map { it.name })
        definition.capabilities.forEachIndexed { index, capability ->
            assertEquals(PackageEffect.WRITE, capability.effect)
            val binding = capability.binding as DeclarativeBinding.Intent
            val request = BindingArguments(capability, emptyMap()).intent(binding)
            val intent = AndroidDeclarativeHost.buildIntent(request)
            assertEquals("moe.zhs.caffeine", intent.component!!.packageName)
            assertEquals("moe.zhs.caffeine.ToggleActivity", intent.component!!.className)
            assertEquals(1 - index, intent.getIntExtra("Status", -1))
            assertEquals(0, intent.flags)
            assertThrows(IllegalArgumentException::class.java) {
                BindingArguments(capability, mapOf("class" to "other.Activity"))
            }
        }
    }

    @Test
    fun `codec rejects dynamic malformed or unscoped components and floors read effects`() {
        assertEquals(
            PackageEffect.HANDOFF,
            PackageCodec
                .decode(source.replace("\"effects\": \"write\"", "\"effects\": \"read\""))
                .capabilities
                .first()
                .effect,
        )
        for (invalid in listOf(
            source.replace("\"package\": \"moe.zhs.caffeine\",", ""),
            source.replace("\"class\": \"moe.zhs.caffeine.ToggleActivity\"", "\"class\": {\"argument\":\"target\"}"),
            source.replace("moe.zhs.caffeine.ToggleActivity", "{target}"),
        )) {
            assertThrows(RuntimeException::class.java) { PackageCodec.decode(invalid) }
        }
    }

    @Test
    fun `missing component refuses launch without changing package identity and allows later handoff`() =
        runBlocking {
            val capability = PackageCodec.decode(source).capabilities.first()
            val request = BindingArguments(capability, emptyMap()).intent(capability.binding as DeclarativeBinding.Intent)
            val intents = AndroidIntentHost()
            val host = AndroidDeclarativeHost(intents, PackageHttpClient(credential = { _, _ -> null }))
            intents.attachAssistant { throw ActivityNotFoundException() }
            val outcome = host.launch(request)
            assertEquals(InvocationStatus.NOT_EXECUTED, outcome.status)
            assertTrue(outcome.message.contains("Install or enable"))
            assertTrue(outcome.message.contains("moe.zhs.caffeine.ToggleActivity"))
            intents.attachAssistant { }
            assertEquals(InvocationStatus.HANDED_OFF, host.launch(request).status)
        }
}
