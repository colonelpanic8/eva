package com.colonelpanic.eva.desktop

import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.MemoryCapabilities
import com.colonelpanic.eva.conversation.ThreadController
import com.colonelpanic.eva.conversation.prompt.PromptConfig
import com.colonelpanic.eva.conversation.prompt.PromptYaml
import com.colonelpanic.eva.data.MemoryStore
import com.colonelpanic.eva.providers.openai.OpenAiResponsesProvider
import com.colonelpanic.eva.providers.openai.SubscriptionAccess
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.channels.FileLock

/**
 * EVA on a desktop computer, built from the same core as the phone: the conversation controller,
 * the capability dispatcher and its journal, and the model provider. [ui] confines every
 * controller call, as the phone's main thread does.
 */
class DesktopHost(
    paths: DesktopPaths,
    private val ui: CoroutineDispatcher,
    /** From [DesktopPaths.lock]: startup recovery assumes no other process is using the journal. */
    private val ownership: FileLock,
    opener: UrlOpener = UrlOpener.system(),
) : AutoCloseable {
    private val journal = JdbcJournal(paths.journal)
    val tokens = ChatGptTokenFile(paths.chatGptTokens)
    val memory = MemoryStore(DirectoryMemoryFiles(paths.memory))
    private val repository = JdbcInvocationRepository(journal)
    val store = JdbcConversationStore(journal)
    private val registry =
        CapabilityRegistry(
            MemoryCapabilities.backends(memory) + DesktopCapabilities.backends(opener),
            MemoryCapabilities.definitions + DesktopCapabilities.definitions,
        )
    private val scope = CoroutineScope(SupervisorJob() + ui)
    private val access = SubscriptionAccess(tokens, CLIENT_VERSION)

    val controller =
        ThreadController(
            registry = registry,
            dispatcher = CapabilityDispatcher(registry, repository),
            repository = repository,
            store = store,
            scope = scope,
            providerFactory = { OpenAiResponsesProvider(access) },
            prompt = { prompt },
        )

    private var closed = false

    /**
     * Ends the attachment, lets running turns finish their journal writes, then closes storage.
     * Turns run outside [scope], so they are awaited through the controller rather than cancelled.
     */
    suspend fun shutdown(timeoutMillis: Long = SHUTDOWN_TIMEOUT_MILLIS) {
        if (closed) return
        closed = true
        withContext(ui) { controller.disconnect() }
        withTimeoutOrNull(timeoutMillis) { controller.working.first { it.isEmpty() } }
        val job = scope.coroutineContext.job
        job.cancel()
        withTimeoutOrNull(timeoutMillis) { job.join() }
        journal.close()
        ownership.release()
        ownership.channel().close()
    }

    override fun close() = runBlocking { shutdown() }

    companion object {
        /** The subscription backend lists models only for clients at or above its version floor. */
        const val CLIENT_VERSION = "0.49.0"
        const val PROMPT_RESOURCE = "/eva-desktop-prompt.yaml"
        const val SHUTDOWN_TIMEOUT_MILLIS = 30_000L

        val prompt: PromptConfig by lazy {
            val text = checkNotNull(DesktopHost::class.java.getResourceAsStream(PROMPT_RESOURCE)) { "The desktop prompt is missing." }
            PromptYaml.decode(text.use { it.readBytes().toString(Charsets.UTF_8) })
        }
    }
}
