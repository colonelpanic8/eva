package com.colonelpanic.eva.data.configuration

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.colonelpanic.eva.conversation.prompt.PromptComponent
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ManagedGitDeviceTest {
    @Test
    fun httpsPushCloneAndPull() {
        val arguments = InstrumentationRegistry.getArguments()
        val remote = arguments.getString("evaGitRemote")
        val branch = arguments.getString("evaGitBranch")
        val username = arguments.getString("evaGitUsername")
        val token = arguments.getString("evaGitToken")
        assumeTrue(
            "Requires evaGitRemote, evaGitBranch, evaGitUsername, and evaGitToken",
            listOf(remote, branch, username, token).all { !it.isNullOrBlank() },
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "managed-git-device-${System.nanoTime()}")
        val bootstrap =
            GitBootstrap(
                remoteUrl = requireNotNull(remote),
                branch = requireNotNull(branch),
                authorName = "EVA device test",
                authorEmail = "eva-device-test@localhost",
                username = requireNotNull(username),
            )
        try {
            val first = ManagedGitRepository(File(root, "first"), bootstrap, token = { token })
            assertEquals(GitCondition.READY, first.connect().condition)
            first.directory.replaceRoot(encoded(configuration("device-initial")), expectedRootFingerprint = null)
            assertEquals(GitCondition.PUSHED, first.commitAndPush().condition)

            val second = ManagedGitRepository(File(root, "second"), bootstrap, token = { token })
            assertEquals(GitCondition.CLONED, second.connect().condition)
            assertEquals(
                "device-initial",
                EvaConfigurationCodec
                    .resolve(second.directory)
                    .configuration.models.text,
            )
            val current = requireNotNull(second.directory.read(EvaConfigurationCodec.FILE_NAME))
            second.directory.replaceRoot(
                encoded(configuration("device-updated")),
                expectedRootFingerprint = EvaConfigurationCodec.fingerprint(current),
            )
            assertEquals(GitCondition.PUSHED, second.commitAndPush().condition)

            assertEquals(GitCondition.PULLED, first.synchronize().condition)
            assertEquals(
                "device-updated",
                EvaConfigurationCodec
                    .resolve(first.directory)
                    .configuration.models.text,
            )
            second.close()
            first.close()
        } finally {
            root.deleteRecursively()
        }
    }

    private fun encoded(configuration: EvaConfiguration) = EvaConfigurationCodec.encode(EvaConfigurationCodec.complete(configuration))

    private fun configuration(model: String) =
        EvaConfiguration(
            models = EvaConfiguration.Models(model, "gpt-realtime", "medium", "medium"),
            voice = EvaConfiguration.Voice(3),
            appearance = EvaConfiguration.Appearance(false),
            capabilities = EvaConfiguration.Capabilities(true),
            messaging = EvaConfiguration.Messaging(false, emptyList()),
            prompt = EvaConfiguration.Prompt("https://example.com/prompt.yaml", listOf(PromptComponent("test", instruction = "Test."))),
            packages =
                EvaConfiguration.Packages(
                    repository = "https://example.com/packages.json",
                    installed = emptyList(),
                    waitMillis = emptyMap(),
                    services = emptyList(),
                ),
            services = EvaConfiguration.Services(emptyMap()),
            extensions = EvaConfiguration.Extensions(emptyList()),
            spotify = EvaConfiguration.Spotify(null),
            credentials = EvaConfiguration.Credentials(emptyList()),
            remembered = EvaConfiguration.Remembered(emptyMap()),
            device = EvaConfiguration.Device(emptyList()),
        )
}
