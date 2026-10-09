package com.colonelpanic.eva.data.configuration

import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver
import org.apache.sshd.common.util.OsUtils
import org.apache.sshd.common.util.io.PathUtils
import org.eclipse.jgit.errors.TransportException
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.Transport
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.sshd.ServerKeyDatabase
import org.eclipse.jgit.transport.sshd.SshdSessionFactory
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder
import org.eclipse.jgit.util.Base64
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import org.eclipse.jgit.api.errors.TransportException as ApiTransportException

/** An SSH Git remote: `user@host:path` or `ssh://user@host[:port]/path`. */
data class SshRemote(
    val user: String,
    val host: String,
    val port: Int,
) {
    val label: String get() = if (port == DEFAULT_PORT) host else "[$host]:$port"

    companion object {
        const val DEFAULT_PORT = 22

        /** Returns null for URLs that are not SSH remotes; throws for malformed SSH remotes. */
        fun parse(url: String): SshRemote? {
            val explicit = url.startsWith("ssh://", ignoreCase = true)
            val scpLike = !url.contains("://") && SCP_LIKE.matches(url)
            if (!explicit && !scpLike) return null
            require(url.none { it.isWhitespace() || it == '?' || it == '#' }) {
                "SSH remote URL cannot contain spaces, a query, or a fragment."
            }
            val uri = runCatching { URIish(url) }.getOrElse { throw IllegalArgumentException("Enter a valid SSH Git remote URL.") }
            val host = uri.host.orEmpty()
            require(host.isNotBlank() && !host.startsWith('-')) { "SSH remote URL must name a host." }
            require(uri.pass == null) { "SSH remote URL cannot contain a password." }
            val user = uri.user.orEmpty()
            require(user.isNotBlank() && !user.startsWith('-')) {
                "SSH remote URL must name the user, for example git@github.com:owner/repo.git."
            }
            require(
                uri.path
                    .orEmpty()
                    .trim('/')
                    .isNotBlank(),
            ) { "SSH remote URL must name a repository path." }
            return SshRemote(user, host.lowercase(), if (uri.port > 0) uri.port else DEFAULT_PORT)
        }

        private val SCP_LIKE = Regex("^[^@/:\\s]+@[^@/:\\s]+:[^\\s]+$")
    }
}

/** EVA's device-local Ed25519 client key; only the 32-byte seed is stored, in SecretStore. */
class GitSshKey private constructor(
    private val seed: ByteArray,
) {
    private val privateKey = EdDSAPrivateKey(EdDSAPrivateKeySpec(seed, ED25519))
    private val publicKeyValue = EdDSAPublicKey(EdDSAPublicKeySpec(privateKey.a, ED25519))

    val keyPair: KeyPair get() = KeyPair(publicKeyValue, privateKey)

    private val blob: ByteArray = sshString("ssh-ed25519".toByteArray()) + sshString(publicKeyValue.abyte)

    /** OpenSSH `authorized_keys` line, suitable for a deploy key. */
    val publicKey: String = "ssh-ed25519 ${Base64.encodeBytes(blob)} $COMMENT"

    val fingerprint: String = sha256Fingerprint(blob)

    fun encoded(): String = Base64.encodeBytes(seed)

    companion object {
        const val COMMENT = "eva-android"
        private val ED25519 = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)

        fun generate(random: SecureRandom = SecureRandom()): GitSshKey = GitSshKey(ByteArray(32).also(random::nextBytes))

        fun decode(value: String): GitSshKey {
            val seed = Base64.decode(value)
            require(seed.size == 32) { "The stored SSH key is invalid; regenerate it in Managed Git settings." }
            return GitSshKey(seed)
        }
    }
}

/**
 * Host key policy: GitHub's published keys are pinned; any other host is trusted on first use and
 * recorded device-locally. A key that differs from the pinned or recorded keys is refused.
 */
class GitSshHostKeys(
    private val load: () -> String,
    private val save: (String) -> Unit,
) {
    data class Entry(
        val host: String,
        val port: Int,
        val key: String,
    ) {
        val label: String get() = SshRemote("git", host, port).label
        val fingerprint: String get() = fingerprintOf(key)
    }

    sealed interface Decision {
        data object Pinned : Decision

        data object Known : Decision

        data class TrustedFirstUse(
            val entry: Entry,
        ) : Decision

        data class Rejected(
            val message: String,
        ) : Decision
    }

    @Synchronized
    fun entries(): List<Entry> =
        load()
            .lineSequence()
            .mapNotNull { line ->
                val parts = line.trim().split(' ')
                if (parts.size != 4) return@mapNotNull null
                Entry(parts[0], parts[1].toIntOrNull() ?: return@mapNotNull null, "${parts[2]} ${parts[3]}")
            }.toList()

    @Synchronized
    fun forget(
        host: String,
        port: Int,
    ) = write(entries().filterNot { it.host == host && it.port == port })

    fun expected(remote: SshRemote): List<String> = if (remote.host in GITHUB_HOSTS) GITHUB_KEYS else recorded(remote).map { it.key }

    @Synchronized
    fun verify(
        remote: SshRemote,
        key: String,
    ): Decision {
        val offered = fingerprintOf(key)
        if (remote.host in GITHUB_HOSTS) {
            return if (key in GITHUB_KEYS) {
                Decision.Pinned
            } else {
                Decision.Rejected(
                    "${remote.label} presented SSH host key $offered, which is not one of GitHub's published host keys. " +
                        "EVA refused to connect. Compare it with https://api.github.com/meta; if GitHub rotated its keys, update EVA.",
                )
            }
        }
        val known = recorded(remote)
        if (known.isEmpty()) {
            val entry = Entry(remote.host, remote.port, key)
            write(entries() + entry)
            return Decision.TrustedFirstUse(entry)
        }
        if (known.any { it.key == key }) return Decision.Known
        return Decision.Rejected(
            "The SSH host key for ${remote.label} changed: EVA trusted ${known.joinToString { it.fingerprint }} " +
                "but the server presented $offered. EVA refused to connect. If the server's key was legitimately replaced, " +
                "choose Forget host key under Managed Git and connect again.",
        )
    }

    private fun recorded(remote: SshRemote) = entries().filter { it.host == remote.host && it.port == remote.port }

    private fun write(entries: List<Entry>) = save(entries.joinToString("\n") { "${it.host} ${it.port} ${it.key}" })

    companion object {
        private val GITHUB_HOSTS = setOf("github.com", "ssh.github.com")

        /** From https://api.github.com/meta (`ssh_keys`). */
        internal val GITHUB_KEYS =
            listOf(
                "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl",
                "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBEmKSENjQEezOmxkZMy7opKgwFB9nkt5YRrYMjNuG5N87uRgg6CLrbo5wAdT/y6v0mKV0U2w0WZ2YB/++Tpockg=",
                "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQCj7ndNxQowgcQnjshcLrqPEiiphnt+VTTvDP6mHBL9j1aNUkY4Ue1gvwnGLVlOhGeYrnZaMgRK6+PKCUXaDbC7qtbW8gIkhL7aGCsOr/C56SJMy/BCZfxd1nWzAOxSDPgVsmerOBYfNqltV9/hWCqBywINIR+5dIg6JTJ72pcEpEjcYgXkE2YEFXV1JHnsKgbLWNlhScqb2UmyRkQyytRLtL+38TGxkxCflmO+5Z8CSSNY7GidjMIZ7Q4zMjA2n1nGrlTDkzwDCsw+wqFPGQA179cnfGWOWRVruj16z6XyvxvjJwbz0wQZ75XK5tKSb7FNyeIEs4TT4jk+S4dhPeAUC5y+bDYirYgM4GC7uEnztnZyaVWQ7B381AK4Qdrwt51ZqExKbQpTUNn+EjqoTwvqNj4kqx5QUCI0ThS/YkOxJCXmPUWZbhjpCg56i+2aB6CmK2JGhn57K5mj0MNdBXA4/WnwH6XoPWJzK5Nyu2zB3nAZp+S5hpQs+p1vN1/wsjk=",
            )

        fun fingerprintOf(key: String): String =
            runCatching { sha256Fingerprint(Base64.decode(key.substringAfter(' ').substringBefore(' '))) }
                .getOrDefault("an unreadable key")
    }
}

/** Device-local SSH inputs; the key is read for every session so regeneration applies immediately. */
class SshAccess(
    val key: () -> GitSshKey?,
    val hostKeys: GitSshHostKeys,
    val home: File,
)

/**
 * JGit SSH transport for one managed remote. It reads no `~/.ssh` files, uses no agent, and offers
 * only EVA's key over public-key authentication.
 */
class ManagedGitSsh(
    private val remote: SshRemote,
    access: SshAccess,
) : AutoCloseable {
    private val hostKeys = access.hostKeys

    @Volatile private var rejection: String? = null

    private val factory: SshdSessionFactory

    init {
        val home = access.home
        val sshDirectory = File(home, ".ssh").apply { mkdirs() }
        factory =
            SshdSessionFactoryBuilder()
                .setHomeDirectory(home)
                .setSshDirectory(sshDirectory)
                .setConfigStoreFactory { _, _, _ -> null }
                .setDefaultKnownHostsFiles { emptyList() }
                .setDefaultIdentities { emptyList() }
                .setDefaultKeysProvider { listOfNotNull(access.key()?.keyPair) }
                .setPreferredAuthentications("publickey")
                .setConnectorFactory(null)
                .setServerKeyDatabase { _, _ -> Database() }
                .build(null)
    }

    fun configure(transport: Transport) {
        (transport as? SshTransport)?.sshSessionFactory = factory
    }

    /** Replaces JGit's generic SSH failure with the specific reason EVA can identify. */
    fun explain(failure: Exception): Exception {
        rejection?.let { return IllegalStateException(it, failure) }
        if (generateSequence<Throwable>(
                failure,
            ) { it.cause }.none { it is TransportException || it is ApiTransportException }
        ) {
            return failure
        }
        val message = generateSequence<Throwable>(failure) { it.cause }.mapNotNull { it.message }.joinToString(" ")
        val hint =
            when {
                message.contains("read only", ignoreCase = true) || message.contains("read-only", ignoreCase = true) -> {
                    "The deploy key is read-only; enable Allow write access for EVA's key on the repository."
                }

                message.contains("auth", ignoreCase = true) || message.contains("publickey", ignoreCase = true) -> {
                    "${remote.host} rejected EVA's SSH key. Add EVA's public key from Managed Git settings to the repository " +
                        "as a deploy key with write access."
                }

                else -> {
                    return failure
                }
            }
        return IllegalStateException("${failure.message.orEmpty()} $hint".trim(), failure)
    }

    fun beginOperation() {
        rejection = null
    }

    override fun close() = factory.close()

    private inner class Database : ServerKeyDatabase {
        override fun lookup(
            connectAddress: String,
            remoteAddress: InetSocketAddress,
            config: ServerKeyDatabase.Configuration,
        ): List<PublicKey> =
            hostKeys.expected(remote).mapNotNull { line ->
                runCatching {
                    PublicKeyEntry.parsePublicKeyEntry(line).resolvePublicKey(null, emptyMap(), PublicKeyEntryResolver.IGNORING)
                }.getOrNull()
            }

        override fun accept(
            connectAddress: String,
            remoteAddress: InetSocketAddress,
            serverKey: PublicKey,
            config: ServerKeyDatabase.Configuration,
            provider: CredentialsProvider?,
        ): Boolean {
            val encoded =
                runCatching { PublicKeyEntry.toString(serverKey) }.getOrElse {
                    rejection =
                        "${remote.label} presented an SSH host key EVA cannot read (${serverKey.algorithm}). EVA refused to connect."
                    return false
                }
            return when (val decision = hostKeys.verify(remote, encoded)) {
                is GitSshHostKeys.Decision.Rejected -> {
                    rejection = decision.message
                    false
                }

                else -> {
                    true
                }
            }
        }
    }

    companion object {
        /** MINA sshd cannot discover a home directory, working directory, or user on Android. */
        fun prepareAndroid(home: File) {
            OsUtils.setAndroid(true)
            OsUtils.setCurrentUser("eva")
            PathUtils.setUserHomeFolderResolver { home.toPath() }
            OsUtils.setCurrentWorkingDirectoryResolver { home.toPath() }
            // Android's platform "BC" provider is a restricted copy; never let MINA select it.
            System.setProperty("org.apache.sshd.security.provider.BC.enabled", "false")
        }
    }
}

private fun sshString(value: ByteArray): ByteArray =
    ByteArrayOutputStream()
        .also { bytes ->
            DataOutputStream(bytes).use {
                it.writeInt(value.size)
                it.write(value)
            }
        }.toByteArray()

private fun sha256Fingerprint(blob: ByteArray): String =
    "SHA256:" + Base64.encodeBytes(MessageDigest.getInstance("SHA-256").digest(blob)).trimEnd('=')
