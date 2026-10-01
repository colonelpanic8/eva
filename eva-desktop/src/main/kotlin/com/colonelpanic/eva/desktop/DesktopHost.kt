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
import kotlinx.coroutines.cancel

/**
 * EVA on a desktop computer, built from the same core as the phone: the conversation controller,
 * the capability dispatcher and its journal, and the model provider. [ui] confines every
 * controller call, as the phone's main thread does.
 */
class DesktopHost(
    paths: DesktopPaths,
    ui: CoroutineDispatcher,
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

    override fun close() {
        scope.cancel()
        journal.close()
    }

    companion object {
        /** The subscription backend lists models only for clients at or above its version floor. */
        const val CLIENT_VERSION = "0.49.0"
        const val PROMPT_RESOURCE = "/eva-desktop-prompt.yaml"

        val prompt: PromptConfig by lazy {
            val text = checkNotNull(DesktopHost::class.java.getResourceAsStream(PROMPT_RESOURCE)) { "The desktop prompt is missing." }
            PromptYaml.decode(text.use { it.readBytes().toString(Charsets.UTF_8) })
        }
    }
}
