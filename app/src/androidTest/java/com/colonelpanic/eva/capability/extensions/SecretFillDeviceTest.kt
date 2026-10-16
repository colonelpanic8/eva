package com.colonelpanic.eva.capability.extensions

import android.app.KeyguardManager
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.colonelpanic.eva.EvaApplication
import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.InvocationRecord
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.ToolProposal
import com.colonelpanic.eva.devicecontrol.PortalFixtureActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Looks a credential up from an installed password-manager extension and types it by handle, all
 * through the live application's registry, extension runtime, journal and [CapabilityDispatcher].
 * Opt-in with `evaSecretFill=true`; values are compared in-process and never logged.
 */
@RunWith(AndroidJUnit4::class)
class SecretFillDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val app = instrumentation.targetContext.applicationContext as EvaApplication
    private val thread = "secret-fill-device-test"

    @Test
    fun looksUpAndFillsACredentialWithoutRevealingIt() =
        runBlocking {
            assumeTrue("Requires evaSecretFill=true", args.getString("evaSecretFill") == "true")
            val target = args.getString("evaExtensionPackage") ?: "app.passwordstore.colonelpanic.debug"
            val dispatcher =
                CapabilityDispatcher(app.registry, app.invocations, executeAdmitted = { proposal, backend ->
                    app.deviceTasks.executeAdmitted(proposal, backend, backend.usesDeviceUi(proposal))
                })
            app.extensions.awaitReady()
            app.extensions.refresh()
            val search = "extension.$target.search_entries"
            val get = "extension.$target.get_credential"
            withTimeout(20_000) { while (get !in app.registry.snapshot.definitions) delay(250) }
            val entry =
                app.extensions.settings.value.entries
                    .single { it.installed.packageName == target }
            val identity = entry.installed.identity as ExtensionIdentity
            val declared = entry.installed.descriptor!!.credentials
            Log.i(
                TAG,
                "described tools=${entry.installed.descriptor!!.capabilities.map { it.name }} credentials=${declared.map { it.name }}",
            )
            val provisioned = (app.extensionCredentials.values(identity, declared) as? CredentialValues.Ready)?.values.orEmpty()
            val power = instrumentation.targetContext.getSystemService(PowerManager::class.java)
            val keyguard = instrumentation.targetContext.getSystemService(KeyguardManager::class.java)
            Log.i(
                TAG,
                "state screenOn=${power.isInteractive} locked=${keyguard.isKeyguardLocked} passphraseSet=${provisioned.isNotEmpty()}",
            )

            val stamp = System.currentTimeMillis()
            var calls = 0

            suspend fun call(
                id: String,
                capability: String,
                arguments: Map<String, String>,
            ): InvocationRecord =
                dispatcher.execute(
                    ToolProposal(
                        "$id-$stamp-${++calls}",
                        capability,
                        arguments,
                        id,
                        app.registry.snapshot.revision,
                        threadId = thread,
                        turnId = "turn-$stamp",
                    ),
                )

            val found = call("search", search, mapOf("query" to (args.getString("evaSecretQuery") ?: "bank")))
            Log.i(TAG, "search status=${found.status} message=${found.message.replace('\n', ' ')}")
            assertEquals(found.message, InvocationStatus.COMPLETED, found.status)

            val looked = call("get", get, mapOf("path" to (args.getString("evaSecretPath") ?: "locked/bank")))
            Log.i(TAG, "get_credential status=${looked.status} message=${looked.message.replace('\n', ' ')}")
            if (args.getString("evaSecretExpect") == "not_executed") {
                assertEquals(InvocationStatus.NOT_EXECUTED, looked.status)
                return@runBlocking
            }
            assertEquals(looked.message, InvocationStatus.COMPLETED, looked.status)
            val password =
                looked.data!!
                    .getValue("secrets")
                    .jsonObject
                    .getValue("password")
                    .jsonObject
            val handle = password.getValue("handle").jsonPrimitive.content
            val label = password.getValue("label").jsonPrimitive.content
            val secret = requireNotNull(app.secrets.resolve(thread, handle)) { "The vault does not hold $handle" }
            Log.i(TAG, "handle=$handle label=$label")
            args.getString("evaSecretSha256")?.let { assertEquals("The vault holds a different value", it, sha256(secret.value)) }

            val hidden = listOf(secret.value) + provisioned.values

            fun assertRedacted(record: InvocationRecord) {
                val stored = app.invocations.let { runBlocking { it.byCallIds(listOf(record.callId)) } }.getValue(record.callId)
                val text = "${stored.message} ${stored.data} ${stored.arguments} ${stored.request}"
                hidden.forEach { assertFalse("${stored.callId} journaled a secret", text.contains(it)) }
            }
            listOf(found, looked).forEach(::assertRedacted)
            assertTrue(looked.message.contains(handle) && looked.message.contains(label))

            if (args.getString("evaSecretType") != "true") return@runBlocking
            // The fixture lives in the test APK's own process, so its fields are checked from outside afterwards.
            instrumentation.targetContext.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .setClassName(instrumentation.context.packageName, PortalFixtureActivity::class.java.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
            delay(2_000)
            for (field in listOf("Fixture input", "Fixture password")) {
                val screen = call("observe", CapabilityRegistry.UI_OBSERVE, emptyMap())
                assertEquals(screen.message, InvocationStatus.COMPLETED, screen.status)
                val ref = Regex("observation (screen-[\\w-]+)").find(screen.message)!!.groupValues[1]
                val line =
                    screen.message.lines().first {
                        it.contains("editable") && (it.contains("\"$field\"") || (field.endsWith("password") && it.contains("<password>")))
                    }
                val node = Regex("^\\[(\\d+)]").find(line)!!.groupValues[1]
                val typed = call("type", CapabilityRegistry.UI_SET_TEXT, mapOf("observationRef" to ref, "node" to node, "secret" to handle))
                Log.i(TAG, "set_text $field status=${typed.status} message=${typed.message.lineSequence().first()}")
                assertEquals(typed.message, InvocationStatus.COMPLETED, typed.status)
                assertRedacted(typed)
            }
        }

    private fun sha256(text: String) =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "EvaSecretFill"
    }
}
