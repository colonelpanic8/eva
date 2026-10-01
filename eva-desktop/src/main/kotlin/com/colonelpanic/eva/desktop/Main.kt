package com.colonelpanic.eva.desktop

import com.colonelpanic.eva.conversation.ConversationEntry
import com.colonelpanic.eva.conversation.ConversationState
import com.colonelpanic.eva.conversation.EntryGroup
import com.colonelpanic.eva.conversation.EntryStatus
import com.colonelpanic.eva.conversation.ProviderStatus
import com.colonelpanic.eva.conversation.ThreadController
import com.colonelpanic.eva.conversation.groups
import com.colonelpanic.eva.providers.openai.ChatGptLogin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.channels.FileLock
import kotlin.coroutines.CoroutineContext
import kotlin.system.exitProcess

private const val USAGE = """Usage: eva-desktop [command]

Commands:
  tray                              Run EVA in the panel tray with a conversation window
  summon                            Show the running tray app's window (for a keybinding)
  chat [--thread ID | --continue]   Talk to EVA in this terminal (default)
  threads                           List conversation threads
  login                             Sign in with a ChatGPT account
  logout                            Forget the ChatGPT sign-in

In a chat, /new starts a new thread and /quit leaves."""

private const val LOCKED = "EVA is already running in another chat or the tray. Quit it first."

fun main(args: Array<String>) {
    val paths = DesktopPaths.fromEnvironment()
    val status =
        when (args.firstOrNull() ?: "chat") {
            "-h", "--help", "help" -> println(USAGE).let { 0 }
            "login" -> owned(paths) { runBlocking { login(paths) } }
            "logout" -> owned(paths) { ChatGptTokenFile(paths.chatGptTokens).clear().let { println("Signed out.").let { 0 } } }
            "threads" -> runBlocking { threads(paths) }
            "chat" -> owned(paths) { lock -> chat(paths, lock, args.drop(1)) }
            "tray" -> owned(paths) { lock -> tray(paths, lock) }
            "summon" -> if (SummonListener.summon(paths.summonSocket)) 0 else System.err.println("EVA's tray app is not running.").let { 1 }
            else -> System.err.println(USAGE).let { 2 }
        }
    exitProcess(status)
}

/**
 * Runs [block] holding the storage lock, so it cannot overlap a chat's recovery or token refresh.
 * The lock is held until the process exits unless the host releases it after a clean shutdown.
 */
private fun owned(
    paths: DesktopPaths,
    block: (FileLock) -> Int,
): Int {
    val lock = paths.lock() ?: return System.err.println(LOCKED).let { 1 }
    return block(lock)
}

private suspend fun login(paths: DesktopPaths): Int {
    val login = ChatGptLogin()
    val code = login.requestCode()
    println("Open ${code.verificationUrl} and enter the code ${code.userCode}")
    val tokens = login.awaitApproval(code)
    ChatGptTokenFile(paths.chatGptTokens).save(tokens)
    println("Signed in${tokens.email?.let { " as $it" }.orEmpty()}.")
    return 0
}

/** Reads the thread list without recovering anything, so it is safe beside a running chat. */
private suspend fun threads(paths: DesktopPaths): Int {
    paths.secure()
    JdbcJournal(paths.journal).use { journal -> JdbcConversationStore(journal).threads().forEach { println("${it.id}  ${it.title}") } }
    return 0
}

private fun tray(
    paths: DesktopPaths,
    lock: FileLock,
): Int {
    if (!ChatGptTokenFile(paths.chatGptTokens).signedIn) {
        System.err.println("Sign in first: eva-desktop login")
        return 1
    }
    return runTray(paths, lock)
}

@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)
private fun chat(
    paths: DesktopPaths,
    lock: FileLock,
    options: List<String>,
): Int {
    if (!ChatGptTokenFile(paths.chatGptTokens).signedIn) {
        System.err.println("Sign in first: eva-desktop login")
        return 1
    }
    val ui = newSingleThreadContext("eva-ui")
    try {
        return DesktopHost(paths, ui, lock).use { host -> runBlocking { converse(host.controller, ui, options) } }
    } finally {
        ui.close()
    }
}

private suspend fun converse(
    controller: ThreadController,
    ui: CoroutineContext,
    options: List<String>,
): Int {
    val loaded = controller.state.first { !it.isLoading }
    loaded.errorMessage?.let {
        System.err.println(it)
        return 1
    }
    when (options.firstOrNull()) {
        "--thread" -> {
            val id = requireNotNull(options.getOrNull(1)) { "--thread needs an ID" }
            withContext(ui) { controller.showThread(id) }
            controller.state.first { it.threadId == id }
        }

        "--continue" -> {
            Unit
        }

        else -> {
            startThread(controller, ui)
        }
    }
    val label = connect(controller, ui) ?: return 1
    println("EVA ($label). /new starts a new thread, /quit leaves.")
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
                reconnectToNewThread(controller, ui) ?: return 1
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
    return 0
}

/** Prints each entry once it settles, and again only if it changes. */
private class Printed {
    private val seen = mutableMapOf<String, ConversationEntry>()

    fun print(state: ConversationState) = groups(state.entries).forEach(::print)

    /** A turn's actions come before its answer, the order they happened in. */
    private fun print(group: EntryGroup) {
        group.children.forEach(::print)
        val entry = group.entry
        if (seen[entry.id] == entry) return
        seen[entry.id] = entry
        when {
            entry.capabilityId != null -> {
                println("  · ${entry.actionTitle ?: entry.capabilityId}: ${label(entry.status)}${entry.result?.let { " — $it" }.orEmpty()}")
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
