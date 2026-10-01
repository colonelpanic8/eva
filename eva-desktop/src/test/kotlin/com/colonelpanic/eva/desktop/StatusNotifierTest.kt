package com.colonelpanic.eva.desktop

import org.freedesktop.dbus.bin.EmbeddedDBusDaemon
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class StatusNotifierTest {
    @get:Rule val folder = TemporaryFolder()

    private class Watcher : StatusNotifierWatcher {
        val registered = CopyOnWriteArrayList<String>()

        override fun registerStatusNotifierItem(service: String) {
            registered += service
        }

        override fun getObjectPath() = "/StatusNotifierWatcher"
    }

    @Test
    fun `the tray item registers with the panel's watcher and toggles on a click`() {
        val address = "unix:path=${folder.root.resolve("bus")}"
        EmbeddedDBusDaemon("$address,listen=true").use { daemon ->
            daemon.startInBackgroundAndWait(10_000)
            DBusConnectionBuilder.forAddress(address).withShared(false).build().use { panel ->
                val watcher = Watcher()
                panel.requestBusName("org.kde.StatusNotifierWatcher")
                panel.exportObject(watcher.objectPath, watcher)
                val clicked = CountDownLatch(1)

                StatusNotifier.show(onActivate = { clicked.countDown() }, busAddress = { address }).getOrThrow().use {
                    val service = watcher.registered.single()
                    assertTrue(service.startsWith("org.kde.StatusNotifierItem-"))
                    val properties = panel.getRemoteObject(service, "/StatusNotifierItem", Properties::class.java)
                    val all = properties.GetAll("org.kde.StatusNotifierItem")
                    assertEquals("EVA", all.getValue("Title").value)
                    assertTrue((all.getValue("IconPixmap").value as List<*>).isNotEmpty())

                    panel.getRemoteObject(service, "/StatusNotifierItem", StatusNotifierItemInterface::class.java).activate(0, 0)
                    assertTrue(clicked.await(5, TimeUnit.SECONDS))
                }
            }
        }
    }

    @Test
    fun `a desktop without a watcher reports why instead of showing nothing`() {
        val address = "unix:path=${folder.root.resolve("bus")}"
        EmbeddedDBusDaemon("$address,listen=true").use { daemon ->
            daemon.startInBackgroundAndWait(10_000)
            assertTrue(StatusNotifier.show(onActivate = {}, busAddress = { address }).isFailure)
        }
    }
}
