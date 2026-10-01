package com.colonelpanic.eva.desktop

import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

/**
 * Listens on a socket in EVA's private data directory, so a keybinding can run
 * `eva-desktop summon` to bring the running window forward. The storage lock guarantees one
 * listener, so a socket left by a dead process is safe to replace.
 */
class SummonListener(
    private val socket: File,
    private val onSummon: () -> Unit,
) : AutoCloseable {
    private val server =
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply {
            socket.delete()
            bind(UnixDomainSocketAddress.of(socket.toPath()))
            restrictTo(socket, "rw-------")
        }

    private val thread =
        Thread({
            while (server.isOpen) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                client.use { if (read(it) == SUMMON) onSummon() }
            }
        }, "eva-summons").apply {
            isDaemon = true
            start()
        }

    override fun close() {
        server.close()
        socket.delete()
    }

    companion object {
        private const val SUMMON = "show"

        /** Asks the running tray app to show its window; false when none is listening. */
        fun summon(socket: File): Boolean =
            runCatching {
                SocketChannel.open(UnixDomainSocketAddress.of(socket.toPath())).use { it.write(ByteBuffer.wrap("$SUMMON\n".toByteArray())) }
            }.isSuccess

        private fun read(channel: SocketChannel): String {
            val buffer = ByteBuffer.allocate(16)
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                if (buffer.array().take(buffer.position()).contains('\n'.code.toByte())) break
            }
            return String(buffer.array(), 0, buffer.position()).trim()
        }
    }
}
