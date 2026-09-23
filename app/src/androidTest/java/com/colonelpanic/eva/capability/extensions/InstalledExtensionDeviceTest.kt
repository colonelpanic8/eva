package com.colonelpanic.eva.capability.extensions

import android.app.KeyguardManager
import android.os.SystemClock
import android.os.UserManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.colonelpanic.eva.adapters.android.AndroidExtensionConnector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real Binder transport against an installed provider, from EVA's own UID, in
 * whatever lock and process state the device is left in. Writes run only when explicitly allowed.
 */
@RunWith(AndroidJUnit4::class)
class InstalledExtensionDeviceTest {
    @Test
    fun describeAndExecuteThroughTheBoundProvider() =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            val target = args.getString("evaExtensionPackage")
            assumeTrue("Requires evaExtensionPackage", !target.isNullOrBlank())
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val connector = AndroidExtensionConnector(context)
            val listing = selectExtensions(connector.scan()).single { it.packageName == target }
            val identity = requireNotNull(listing.identity) { listing.problem.orEmpty() }
            val manager = ExtensionConnectionManager(connector, SystemClock::elapsedRealtime)
            val locked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
            val unlocked = context.getSystemService(UserManager::class.java).isUserUnlocked

            val started = SystemClock.elapsedRealtime()
            val described = manager.describe(identity)
            assertTrue("describe: $described", described is ExtensionExchange.Reply)
            val descriptor = requireNotNull(ExtensionProtocol.describe((described as ExtensionExchange.Reply).json).descriptor)
            Log.i(
                TAG,
                "describe locked=$locked userUnlocked=$unlocked millis=${SystemClock.elapsedRealtime() - started} " +
                    "revision=${descriptor.revision} tools=${descriptor.capabilities.map { "${it.name}:${it.effect}" }}",
            )

            val name = args.getString("evaExtensionCapability") ?: return@runBlocking
            val capability = descriptor.capabilities.single { it.name == name }
            assumeTrue(
                "Writes require evaExtensionAllowWrite=true",
                capability.effect == Effect.READ || args.getString("evaExtensionAllowWrite") == "true",
            )
            val invocation = ExtensionBackend.invocationId("device-test:${System.currentTimeMillis()}")
            val executed = SystemClock.elapsedRealtime()
            val exchange =
                manager.execute(identity, invocation, descriptor.revision, capability, args.getString("evaExtensionArguments") ?: "{}")
            assertTrue("execute: $exchange", exchange is ExtensionExchange.Reply)
            val result =
                ExtensionProtocol.executeResult(
                    (exchange as ExtensionExchange.Reply).json,
                    capability.maxResultBytes,
                    capability.outputSchema,
                )
            Log.i(
                TAG,
                "execute $name invocation=$invocation locked=$locked millis=${SystemClock.elapsedRealtime() - executed} " +
                    "status=${result.outcome.status} reason=${result.reasonCode} state=${result.outcome.data?.get("state")}",
            )
        }

    private companion object {
        const val TAG = "EvaExtensionDevice"
    }
}
