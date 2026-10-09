package com.colonelpanic.eva.data.configuration

import com.colonelpanic.eva.conversation.prompt.PromptComponent
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.server.subsystem.SubsystemFactory
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.junit.ssh.SshTestGitServer
import org.eclipse.jgit.lib.Constants
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator

class ManagedGitSshTest {
    private val servers = mutableListOf<SshTestGitServer>()

    @After
    fun stopServers() = servers.forEach { it.stop() }

    @Test
    fun `ssh remotes parse in scp and url forms while other urls are left to https validation`() {
        assertEquals(SshRemote("git", "github.com", 22), SshRemote.parse("git@github.com:colonelpanic8/eva-config.git"))
        assertEquals(SshRemote("git", "git.example.org", 2222), SshRemote.parse("ssh://git@git.example.org:2222/srv/eva.git"))
        assertNull(SshRemote.parse("https://github.com/colonelpanic8/eva-config.git"))
        listOf(
            "ssh://example.com/eva.git",
            "ssh://git:secret@example.com/eva.git",
            "ssh://git@example.com/",
            "ssh://git@example.com/eva.git?x=1",
            "git@example.com:eva.git#main",
        ).forEach { url -> assertTrue(url, runCatching { SshRemote.parse(url) }.isFailure) }
        validateGitBootstrap(GitBootstrap("git@github.com:colonelpanic8/eva-config.git"))
        validateGitBootstrap(GitBootstrap("ssh://git@github.com/colonelpanic8/eva-config.git"))
        assertTrue(runCatching { validateGitBootstrap(GitBootstrap("http://example.com/eva.git")) }.isFailure)
    }

    @Test
    fun `stored seed restores the same ed25519 key and openssh public key`() {
        val key = GitSshKey.generate()
        val restored = GitSshKey.decode(key.encoded())

        assertEquals(key.publicKey, restored.publicKey)
        assertTrue(key.publicKey.startsWith("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5"))
        assertEquals(PublicKeyEntry.toString(restored.keyPair.public), key.publicKey.substringBeforeLast(' '))
        assertFalse(key.publicKey.contains(key.encoded()))
        assertTrue(runCatching { GitSshKey.decode("c2hvcnQ=") }.isFailure)
    }

    @Test
    fun `github host keys are pinned and other hosts are trusted on first use`() {
        var stored = ""
        val hostKeys = GitSshHostKeys({ stored }, { stored = it })
        val github = SshRemote("git", "github.com", 22)
        assertEquals(
            "SHA256:+DiY3wvvV6TuJJhbpZisF/zLDA0zPMSvHdkr4UvCOqU",
            GitSshHostKeys.fingerprintOf(GitSshHostKeys.GITHUB_KEYS.first()),
        )
        GitSshHostKeys.GITHUB_KEYS.forEach { assertEquals(GitSshHostKeys.Decision.Pinned, hostKeys.verify(github, it)) }
        val impostor = hostKey().public.let(PublicKeyEntry::toString)
        val rejected = hostKeys.verify(github, impostor) as GitSshHostKeys.Decision.Rejected
        assertTrue(rejected.message.contains("api.github.com/meta"))
        assertEquals("", stored)

        val server = SshRemote("git", "git.example.org", 2222)
        val first = hostKey().public.let(PublicKeyEntry::toString)
        val second = hostKey().public.let(PublicKeyEntry::toString)
        assertTrue(hostKeys.verify(server, first) is GitSshHostKeys.Decision.TrustedFirstUse)
        assertEquals(GitSshHostKeys.Decision.Known, hostKeys.verify(server, first))
        assertTrue(hostKeys.verify(SshRemote("git", "git.example.org", 22), second) is GitSshHostKeys.Decision.TrustedFirstUse)
        val changed = hostKeys.verify(server, second) as GitSshHostKeys.Decision.Rejected
        assertTrue(changed.message.contains(GitSshHostKeys.fingerprintOf(first)))
        assertTrue(changed.message.contains(GitSshHostKeys.fingerprintOf(second)))

        hostKeys.forget("git.example.org", 2222)
        assertTrue(hostKeys.verify(server, second) is GitSshHostKeys.Decision.TrustedFirstUse)
    }

    @Test
    fun `managed checkout pushes and clones over ssh with a recorded host key`() {
        val fixture = fixture()
        val first = fixture.repository("first")
        assertEquals(GitCondition.READY, first.connect().condition)
        assertEquals(1, fixture.hostKeys.entries().size)
        first.directory.replaceRoot(encoded("initial"), null)
        assertEquals(GitCondition.PUSHED, first.commitAndPush().condition)
        first.close()
        assertNotNull(Git.open(fixture.remote).use { it.repository.resolve(Constants.R_HEADS + "main") })

        val second = fixture.repository("second")
        assertEquals(GitCondition.CLONED, second.connect().condition)
        assertEquals(
            "initial",
            EvaConfigurationCodec
                .resolve(second.directory)
                .configuration.models.text,
        )
        second.close()
    }

    @Test
    fun `a changed host key refuses the connection with both fingerprints`() {
        val fixture = fixture()
        fixture.repository("first").use { it.connect() }
        val trusted = fixture.hostKeys.entries().single()
        val replaced = hostKey()
        fixture.server.addHostKey(replaced, true)

        val failure = runCatching { fixture.repository("second").use { it.connect() } }.exceptionOrNull()

        val message = requireNotNull(failure?.message)
        assertTrue(message, message.contains("changed"))
        assertTrue(message, message.contains(trusted.fingerprint))
        assertEquals(listOf(trusted), fixture.hostKeys.entries())
    }

    @Test
    fun `an unregistered client key explains how to add the deploy key`() {
        val fixture = fixture()
        val failure =
            runCatching {
                fixture.repository("first", key = GitSshKey.generate()).use { it.connect() }
            }.exceptionOrNull()

        val message = requireNotNull(failure?.message)
        assertTrue(message, message.contains("deploy key with write access"))
    }

    private fun fixture(): Fixture {
        val root = Files.createTempDirectory("eva-managed-git-ssh").toFile()
        val remote = File(root, "remote.git")
        val repository =
            Git
                .init()
                .setBare(true)
                .setDirectory(remote)
                .setInitialBranch("main")
                .call()
                .repository
        val key = GitSshKey.generate()
        val server =
            object : SshTestGitServer("git", key.keyPair.public, repository, hostKey()) {
                override fun configureSubsystems(): List<SubsystemFactory> = emptyList()
            }
        servers += server
        val port = server.start()
        return Fixture(root, remote, key, server, port)
    }

    private fun hostKey(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

    private fun encoded(model: String) =
        EvaConfigurationCodec.encode(
            EvaConfigurationCodec.complete(
                EvaConfiguration(
                    models = EvaConfiguration.Models(model, "gpt-realtime", "medium", "medium"),
                    voice = EvaConfiguration.Voice(3),
                    appearance = EvaConfiguration.Appearance(false),
                    capabilities = EvaConfiguration.Capabilities(true),
                    messaging = EvaConfiguration.Messaging(false, emptyList()),
                    prompt =
                        EvaConfiguration.Prompt("https://example.com/prompt.yaml", listOf(PromptComponent("test", instruction = "Test."))),
                    packages = EvaConfiguration.Packages(listOf("https://example.com/packages.json"), emptyList(), emptyMap(), emptyList()),
                    services = EvaConfiguration.Services(emptyMap()),
                    extensions = EvaConfiguration.Extensions(emptyList()),
                    spotify = EvaConfiguration.Spotify(null),
                    credentials = EvaConfiguration.Credentials(emptyList()),
                    remembered = EvaConfiguration.Remembered(emptyMap()),
                    device = EvaConfiguration.Device(emptyList()),
                ),
            ),
        )

    private class Fixture(
        val root: File,
        val remote: File,
        val key: GitSshKey,
        val server: SshTestGitServer,
        port: Int,
    ) {
        private var knownHosts = ""
        val hostKeys = GitSshHostKeys({ knownHosts }, { knownHosts = it })
        private val remoteUrl = "ssh://git@localhost:$port/remote.git"

        fun repository(
            name: String,
            key: GitSshKey = this.key,
        ) = ManagedGitRepository(
            checkout = File(root, name),
            bootstrap = GitBootstrap(remoteUrl),
            token = { null },
            ssh = SshAccess(key = { key }, hostKeys = hostKeys, home = File(root, "ssh-home").apply { mkdirs() }),
        )
    }
}
