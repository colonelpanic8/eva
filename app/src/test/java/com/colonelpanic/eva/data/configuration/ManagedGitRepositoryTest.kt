package com.colonelpanic.eva.data.configuration

import com.colonelpanic.eva.conversation.prompt.PromptComponent
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ManagedGitRepositoryTest {
    @Test
    fun `empty remote receives initial snapshot and another checkout clones it`() {
        val fixture = fixture()
        val first = fixture.repository("first")
        assertEquals(GitCondition.READY, first.connect().condition)
        first.directory.replaceRoot(encoded(configuration("initial")), null)
        assertEquals(GitCondition.PUSHED, first.commitAndPush().condition)
        first.close()

        val second = fixture.repository("second")
        val cloned = second.connect()
        assertEquals(GitCondition.CLONED, cloned.condition)
        assertEquals(
            "initial",
            EvaConfigurationCodec
                .resolve(second.directory)
                .configuration.models.text,
        )

        second.rollback(cloned)
        assertNull(second.directory.read(EvaConfigurationCodec.FILE_NAME))
        assertNull(second.localRepository().resolve(Constants.R_HEADS + "main"))
        assertNull(second.localRepository().resolve(Constants.R_REMOTES + "origin/main"))
        second.close()
    }

    @Test
    fun `fast forward pull validates and updates readable checkout`() {
        val fixture = fixtureWithInitial("one")
        val first = fixture.repository("first").also { it.connect() }
        val second = fixture.repository("second").also { it.connect() }
        update(second, "two")
        assertEquals(GitCondition.PUSHED, second.commitAndPush().condition)

        val pulled = first.synchronize()
        assertEquals(GitCondition.PULLED, pulled.condition)
        assertEquals(
            "two",
            EvaConfigurationCodec
                .resolve(first.directory)
                .configuration.models.text,
        )
        first.close()
        second.close()
    }

    @Test
    fun `invalid remote configuration does not move local head or checkout`() {
        val fixture = fixtureWithInitial("valid")
        val local = fixture.repository("local").also { it.connect() }
        val beforeHead = local.localRepository().resolve(Constants.R_HEADS + "main")
        val attackerDir = File(fixture.root, "attacker")
        Git.cloneRepository().setURI(fixture.remote.toURI().toString()).setDirectory(attackerDir).setBranch("main").call().use { git ->
            File(attackerDir, EvaConfigurationCodec.FILE_NAME).writeText("not: [valid")
            git.add().addFilepattern(EvaConfigurationCodec.FILE_NAME).call()
            git
                .commit()
                .setMessage("invalid")
                .setAuthor("Test", "test@example.com")
                .call()
            git.push().setRemote("origin").call()
        }

        assertTrue(runCatching { local.synchronize() }.isFailure)
        assertEquals(beforeHead, local.localRepository().resolve(Constants.R_HEADS + "main"))
        assertEquals(
            "valid",
            EvaConfigurationCodec
                .resolve(local.directory)
                .configuration.models.text,
        )
        local.close()
    }

    @Test
    fun `Git LFS attributes are rejected before checkout`() {
        val fixture = fixtureWithInitial("valid")
        val attackerDir = File(fixture.root, "attacker-lfs")
        Git.cloneRepository().setURI(fixture.remote.toURI().toString()).setDirectory(attackerDir).setBranch("main").call().use { git ->
            File(attackerDir, ".gitattributes").writeText("eva.yaml filter=lfs diff=lfs merge=lfs -text\n")
            git.add().addFilepattern(".gitattributes").call()
            git
                .commit()
                .setMessage("lfs")
                .setAuthor("Test", "test@example.com")
                .call()
            git.push().setRemote("origin").call()
        }
        val local = fixture.repository("local-lfs")

        assertTrue(
            runCatching { local.connect() }
                .exceptionOrNull()
                ?.message
                .orEmpty()
                .contains("LFS"),
        )
        assertNull(local.directory.read(EvaConfigurationCodec.FILE_NAME))
        local.close()
    }

    @Test
    fun `existing checkout remains readable when reconnect is offline`() {
        val fixture = fixtureWithInitial("offline")
        fixture.repository("local").use { it.connect() }
        check(fixture.remote.renameTo(File(fixture.root, "remote-offline.git")))

        fixture.repository("local").use { repository ->
            assertTrue(runCatching { repository.connect() }.isFailure)
            assertEquals(
                "offline",
                EvaConfigurationCodec
                    .resolve(repository.directory)
                    .configuration.models.text,
            )
        }
    }

    @Test
    fun `remote divergence is visible and never force pushed`() {
        val fixture = fixtureWithInitial("base")
        val first = fixture.repository("first").also { it.connect() }
        val second = fixture.repository("second").also { it.connect() }
        localCommit(first, "first")
        update(second, "second")
        assertEquals(GitCondition.PUSHED, second.commitAndPush().condition)

        val result = first.synchronize()
        assertEquals(GitCondition.CONFLICT, result.condition)
        assertTrue(result.pendingCommits > 0)
        assertEquals(
            "first",
            EvaConfigurationCodec
                .resolve(first.directory)
                .configuration.models.text,
        )
        first.close()
        second.close()
    }

    @Test
    fun `deleted remote branch is not silently recreated`() {
        val fixture = fixtureWithInitial("base")
        val local = fixture.repository("local").also { it.connect() }
        Git.open(fixture.remote).use { git ->
            git.repository.updateRef(Constants.R_HEADS + "main").apply {
                setForceUpdate(true)
                delete()
            }
        }

        assertEquals(GitCondition.CONFLICT, local.synchronize().condition)
        update(local, "kept-local")
        assertTrue(runCatching { local.commitAndPush() }.isFailure)
        assertNull(fixture.remoteHead())
        local.close()
    }

    @Test
    fun `push rejection leaves scoped local commit and excludes unrelated staged file`() {
        val fixture = fixtureWithInitial("base", unrelated = "remote")
        val local = fixture.repository("local").also { it.connect() }
        val checkout = local.checkout
        File(checkout, "unrelated.txt").writeText("must-not-commit")
        Git.open(checkout).use { it.add().addFilepattern("unrelated.txt").call() }
        update(local, "pending")
        val remoteBefore = fixture.remoteHead()

        fixture.failNextPush = true
        assertTrue(runCatching { local.commitAndPush() }.isFailure)

        val localHead = local.localRepository().resolve(Constants.R_HEADS + "main")
        assertTrue(localHead != remoteBefore)
        Git.open(checkout).use { git ->
            val committed = git.repository.resolve("HEAD:unrelated.txt")
            val remoteBlob = git.repository.resolve("${remoteBefore.name}:unrelated.txt")
            assertEquals(remoteBlob, committed)
        }
        local.close()
    }

    @Test
    fun `token redaction covers raw and encoded forms`() {
        val token = "secret /+value"
        val failure = IllegalStateException("failed secret%20%2F%2Bvalue and secret+%2F%2Bvalue and $token")
        val message = safeGitError(failure, token)
        assertFalse(message.contains("secret", ignoreCase = true))
        assertFalse(message.contains("value", ignoreCase = true))
    }

    @Test
    fun `bootstrap rejects unsafe transports urls and refs`() {
        listOf(
            GitBootstrap("ssh://example.com/eva.git"),
            GitBootstrap("https://user:token@example.com/eva.git"),
            GitBootstrap("https://example.com/eva.git#main"),
            GitBootstrap("https://example.com/eva.git?token=value"),
            GitBootstrap("https://example.com/eva.git", branch = "../main"),
        ).forEach { value -> assertTrue(value.toString(), runCatching { validateGitBootstrap(value) }.isFailure) }
    }

    @Test
    fun `checkout identity cannot be reused for another remote`() {
        val fixture = fixture()
        fixture.repository("local").close()
        val otherRemote = File(fixture.root, "other.git")
        Git
            .init()
            .setBare(true)
            .setDirectory(otherRemote)
            .setInitialBranch("main")
            .call()
            .close()

        assertTrue(
            runCatching {
                ManagedGitRepository(
                    File(fixture.root, "local"),
                    GitBootstrap(otherRemote.toURI().toString()),
                    token = { null },
                    allowLocalTransportForTests = true,
                )
            }.isFailure,
        )
    }

    @Test
    fun `managed repository pins TLS verification and disables redirects`() {
        val fixture = fixture()
        fixture.repository("local").close()

        Git.open(File(fixture.root, "local")).use { git ->
            assertTrue(git.repository.config.getBoolean("http", null, "sslVerify", false))
            assertEquals("false", git.repository.config.getString("http", null, "followRedirects"))
        }
    }

    private fun fixtureWithInitial(
        model: String,
        unrelated: String? = null,
    ): Fixture =
        fixture().also { fixture ->
            val initial = fixture.repository("seed")
            initial.connect()
            initial.directory.replaceRoot(encoded(configuration(model)), null)
            if (unrelated != null) File(initial.checkout, "unrelated.txt").writeText(unrelated)
            initial.commitAndPush()
            if (unrelated != null) {
                Git.open(initial.checkout).use { git ->
                    git.add().addFilepattern("unrelated.txt").call()
                    git
                        .commit()
                        .setMessage("unrelated seed")
                        .setAuthor("Test", "test@example.com")
                        .call()
                    git.push().setRemote("origin").call()
                }
            }
            initial.close()
        }

    private fun fixture(): Fixture {
        val root = Files.createTempDirectory("eva-managed-git").toFile()
        val remote = File(root, "remote.git")
        Git
            .init()
            .setBare(true)
            .setDirectory(remote)
            .setInitialBranch("main")
            .call()
            .close()
        return Fixture(root, remote)
    }

    private fun update(
        repository: ManagedGitRepository,
        model: String,
    ) {
        val current = repository.directory.read(EvaConfigurationCodec.FILE_NAME)
        repository.directory.replaceRoot(encoded(configuration(model)), current?.let(EvaConfigurationCodec::fingerprint))
    }

    private fun localCommit(
        repository: ManagedGitRepository,
        model: String,
    ) {
        update(repository, model)
        val checkout = repository.checkout
        Git.open(checkout).use { git ->
            git.add().addFilepattern(EvaConfigurationCodec.FILE_NAME).call()
            git
                .commit()
                .setMessage("local divergence")
                .setAuthor("Test", "test@example.com")
                .call()
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
            packages = EvaConfiguration.Packages("https://example.com/packages.json", emptyMap(), emptyList(), emptyMap(), emptyList()),
            services = EvaConfiguration.Services(emptyMap()),
            extensions = EvaConfiguration.Extensions(emptyList()),
            spotify = EvaConfiguration.Spotify(null),
            credentials = EvaConfiguration.Credentials(emptyList()),
            remembered = EvaConfiguration.Remembered(emptyMap()),
            device = EvaConfiguration.Device(emptyList()),
        )

    private data class Fixture(
        val root: File,
        val remote: File,
    ) {
        private val remoteUrl = remote.toURI().toString()
        var failNextPush: Boolean = false

        fun repository(name: String) =
            ManagedGitRepository(
                checkout = File(root, name),
                bootstrap = GitBootstrap(remoteUrl),
                token = { null },
                allowLocalTransportForTests = true,
                beforePushForTests = {
                    if (failNextPush) {
                        failNextPush = false
                        check(remote.renameTo(File(root, "remote-offline.git")))
                    }
                },
            )

        fun remoteHead() = Git.open(remote).use { it.repository.resolve(Constants.R_HEADS + "main") }
    }

    private fun ManagedGitRepository.localRepository() = Git.open(checkout).repository
}
