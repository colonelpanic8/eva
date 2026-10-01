package com.colonelpanic.eva.desktop

import org.freedesktop.dbus.bin.EmbeddedDBusDaemon
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                    assertEquals("EVA", properties.Get<Any>("org.kde.StatusNotifierItem", "Title"))
                    assertTrue((properties.Get<Any>("org.kde.StatusNotifierItem", "IconPixmap") as List<*>).isNotEmpty())

                    panel.getRemoteObject(service, "/StatusNotifierItem", StatusNotifierItemInterface::class.java).activate(0, 0)
                    assertTrue(clicked.await(5, TimeUnit.SECONDS))
                }
            }
        }
    }

    @Test
    fun `the icon registers again when the panel restarts and reports when it is gone`() {
        val address = "unix:path=${folder.root.resolve("bus")}"
        EmbeddedDBusDaemon("$address,listen=true").use { daemon ->
            daemon.startInBackgroundAndWait(10_000)
            val first = DBusConnectionBuilder.forAddress(address).withShared(false).build()
            first.requestBusName("org.kde.StatusNotifierWatcher")
            first.exportObject("/StatusNotifierWatcher", Watcher())
            StatusNotifier.show(onActivate = {}, busAddress = { address }).getOrThrow().use { item ->
                assertTrue(item.visible())
                first.close()
                awaitVisible(item, false)

                DBusConnectionBuilder.forAddress(address).withShared(false).build().use { restarted ->
                    val watcher = Watcher()
                    // A restarting panel can own the name before it serves the watcher object.
                    restarted.requestBusName("org.kde.StatusNotifierWatcher")
                    Thread.sleep(700)
                    assertFalse(item.visible())
                    restarted.exportObject(watcher.objectPath, watcher)
                    awaitVisible(item, true)
                    assertEquals(1, watcher.registered.size)
                }
            }
        }
    }

    private fun awaitVisible(
        item: StatusNotifier,
        expected: Boolean,
    ) {
        val deadline = System.nanoTime() + 15_000_000_000
        while (item.visible() != expected) {
            check(System.nanoTime() < deadline) { "The icon never became visible=$expected" }
            Thread.sleep(50)
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
