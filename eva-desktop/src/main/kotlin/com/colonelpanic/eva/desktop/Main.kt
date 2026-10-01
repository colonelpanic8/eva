package com.colonelpanic.eva.desktop

import com.colonelpanic.eva.conversation.ConversationEntry
import com.colonelpanic.eva.conversation.ConversationState
import com.colonelpanic.eva.conversation.EntryGroup
import com.colonelpanic.eva.conversation.EntryStatus
import com.colonelpanic.eva.conversation.ProviderStatus
import com.colonelpanic.eva.conversation.groups
import com.colonelpanic.eva.providers.openai.ChatGptLogin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.system.exitProcess

private const val USAGE = """Usage: eva-desktop [command]

Commands:
  chat [--thread ID | --continue]   Talk to EVA (default; starts a new thread)
  threads                           List conversation threads
  login                             Sign in with a ChatGPT account
  logout                            Forget the ChatGPT sign-in

In a chat, /new starts a new thread and /quit leaves."""

@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)
fun main(args: Array<String>) {
    val paths = DesktopPaths.fromEnvironment()
    val command = args.firstOrNull() ?: "chat"
    if (command in setOf("-h", "--help", "help")) {
        println(USAGE)
        return
    }
    val ui = newSingleThreadContext("eva-ui")
    val status =
        DesktopHost(paths, ui).use { host ->
            runBlocking {
                when (command) {
                    "login" -> login(host)
                    "logout" -> host.tokens.clear().let { println("Signed out.").let { 0 } }
                    "threads" -> threads(host)
                    "chat" -> chat(host, ui, args.drop(1))
                    else -> System.err.println(USAGE).let { 2 }
                }
            }
        }
    ui.close()
    exitProcess(status)
}

private suspend fun login(host: DesktopHost): Int {
    val login = ChatGptLogin()
    val code = login.requestCode()
    println("Open ${code.verificationUrl} and enter the code ${code.userCode}")
    val tokens = login.awaitApproval(code)
    host.tokens.save(tokens)
    println("Signed in${tokens.email?.let { " as $it" }.orEmpty()}.")
    return 0
}

private suspend fun threads(host: DesktopHost): Int {
    host.store.threads().forEach { println("${it.id}  ${it.title}") }
    return 0
}

private suspend fun chat(
    host: DesktopHost,
    ui: kotlin.coroutines.CoroutineContext,
    options: List<String>,
): Int {
    if (!host.tokens.signedIn) {
        System.err.println("Sign in first: eva-desktop login")
        return 1
    }
    val controller = host.controller
    val loaded = controller.state.first { !it.isLoading }
    loaded.errorMessage?.let {
        System.err.println(it)
        return 1
    }
    // Thread changes land asynchronously; connecting first would attach to the thread shown before.
    val previous = loaded.threadId
    when {
        options.firstOrNull() == "--thread" -> {
            val id = requireNotNull(options.getOrNull(1)) { "--thread needs an ID" }
            withContext(ui) { controller.showThread(id) }
            controller.state.first { it.threadId == id }
        }

        options.firstOrNull() == "--continue" -> {
            Unit
        }

        else -> {
            withContext(ui) { controller.newThread() }
            controller.state.first { it.threadId != null && it.threadId != previous }
        }
    }
    withContext(ui) { controller.connect("") }
    val connected =
        withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS) {
            controller.state.first {
                it.providerStatus == ProviderStatus.CONNECTED ||
                    it.errorMessage != null
            }
        }
    if (connected?.providerStatus != ProviderStatus.CONNECTED) {
        System.err.println(connected?.errorMessage ?: connected?.providerMessage ?: "Could not connect to the model.")
        return 1
    }
    println("EVA (${connected.providerLabel}). /new starts a new thread, /quit leaves.")
    val shown = Printed()
    shown.print(controller.state.value)
    while (true) {
        print("you> ")
        System.out.flush()
        val line = readlnOrNull()?.trim() ?: break
        when {
            line.isEmpty() -> {
                continue
            }

            line == "/quit" -> {
                break
            }

            line == "/new" -> {
                val shownBefore = controller.state.value.threadId
                withContext(ui) { controller.newThread() }
                controller.state.first { it.threadId != shownBefore }
                println("New thread.")
                continue
            }
        }
        val before =
            controller.state.value.entries
                .map { it.id }
                .toSet()
        val accepted =
            withContext(ui) {
                controller.submit(line)
                controller.state.value.isSubmitting
            }
        if (!accepted) {
            println(controller.state.value.providerMessage ?: "EVA did not take that request.")
            continue
        }
        val settled =
            controller.state.first { state ->
                !state.isSubmitting && !state.working &&
                    state.entries.any { it.id !in before && it.request == line } &&
                    state.entries.none { it.status == EntryStatus.PENDING || it.status == EntryStatus.DISPATCHING }
            }
        shown.print(settled)
        settled.providerMessage?.let(::println)
    }
    withContext(ui) { controller.disconnect() }
    return 0
}

/** Prints each entry once it settles, and again only if it changes. */
private class Printed {
    private val seen = mutableMapOf<String, ConversationEntry>()
    val ids: Set<String> get() = seen.keys

    fun print(state: ConversationState) = groups(state.entries).forEach(::print)

    /** A turn's actions come before its answer, the order they happened in. */
    private fun print(group: EntryGroup) {
        group.children.forEach(::print)
        val entry = group.entry
        if (seen[entry.id] == entry) return
        seen[entry.id] = entry
        when {
            entry.capabilityId != null -> {
                println(
                    "  · ${entry.actionTitle ?: entry.capabilityId}: ${label(entry.status)}${entry.result?.let { " — $it" }.orEmpty()}",
                )
            }

            entry.status == EntryStatus.SESSION -> {
                println("  (${entry.response})")
            }

            entry.response.isNotBlank() -> {
                println("eva> ${entry.response}")
            }
        }
    }

    private fun label(status: EntryStatus) = status.name.lowercase().replace('_', ' ')
}

private const val CONNECT_TIMEOUT_MILLIS = 30_000L
