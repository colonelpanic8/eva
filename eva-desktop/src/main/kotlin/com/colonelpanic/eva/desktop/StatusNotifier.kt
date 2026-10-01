package com.colonelpanic.eva.desktop

import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusMemberName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import java.awt.image.BufferedImage
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The tray side of the StatusNotifierItem protocol, which Wayland and modern X11 panels host over D-Bus. */
@DBusInterfaceName("org.kde.StatusNotifierItem")
interface StatusNotifierItemInterface : DBusInterface {
    @DBusMemberName("Activate")
    fun activate(
        x: Int,
        y: Int,
    )

    @DBusMemberName("SecondaryActivate")
    fun secondaryActivate(
        x: Int,
        y: Int,
    )

    @DBusMemberName("ContextMenu")
    fun contextMenu(
        x: Int,
        y: Int,
    )

    @DBusMemberName("Scroll")
    fun scroll(
        delta: Int,
        orientation: String,
    )
}

@DBusInterfaceName("org.kde.StatusNotifierWatcher")
interface StatusNotifierWatcher : DBusInterface {
    @DBusMemberName("RegisterStatusNotifierItem")
    fun registerStatusNotifierItem(service: String)
}

/** One icon image: width, height, and ARGB32 pixels in network byte order. */
class IconPixmap(
    @field:Position(0) val width: Int,
    @field:Position(1) val height: Int,
    @field:Position(2) val pixels: ByteArray,
) : Struct()

/**
 * EVA's tray icon. Any click toggles the window; there is no D-Bus menu, so quitting is in the
 * window. [close] removes the icon.
 */
class StatusNotifier private constructor(
    private val connection: DBusConnection,
    private val service: String,
    private val onActivate: () -> Unit,
) : StatusNotifierItemInterface,
    Properties,
    PanelIcon {
    /** The unique bus name of the watcher that accepted the icon; a restarted panel has a new one. */
    @Volatile private var acceptedBy: String? = null

    private val bus get() = connection.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java)

    override fun visible(): Boolean = acceptedBy != null && runCatching { bus.GetNameOwner(WATCHER) }.getOrNull() == acceptedBy

    /** Registers with [owner], the watcher's current unique name; false if it refused or is not ready. */
    private fun register(owner: String): Boolean =
        runCatching {
            connection
                .getRemoteObject(owner, WATCHER_PATH, StatusNotifierWatcher::class.java, true)
                .registerStatusNotifierItem(service)
        }.onSuccess { acceptedBy = owner }
            .isSuccess

    private val retries =
        Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "eva-tray-registration").apply { isDaemon = true } }

    /**
     * Registers with a watcher that just took the name, retrying while it keeps it: a restarting
     * panel may own the name before it exports the watcher object. Delays grow from
     * [FIRST_RETRY_MILLIS] to [LAST_RETRY_MILLIS] and stay there; a new owner or close stops them.
     */
    private fun follow(
        owner: String,
        delayMillis: Long,
    ) {
        retries.schedule({
            val current = runCatching { bus.GetNameOwner(WATCHER) }.getOrNull()
            if (current == owner && !register(owner)) follow(owner, nextDelay(delayMillis))
        }, delayMillis, TimeUnit.MILLISECONDS)
    }

    private fun watchOwnership() {
        connection.addSigHandler(DBus.NameOwnerChanged::class.java) { signal ->
            if (signal.name == WATCHER) {
                acceptedBy = null
                if (signal.newOwner.isNotEmpty()) follow(signal.newOwner, delayMillis = 0)
            }
        }
    }

    private val properties: Map<String, Variant<*>> =
        mapOf(
            "Category" to Variant("ApplicationStatus"),
            "Id" to Variant("eva"),
            "Title" to Variant("EVA"),
            "Status" to Variant("Active"),
            "IconName" to Variant(""),
            "IconPixmap" to Variant(listOf(pixmap(PanelIcon.image())), "a(iiay)"),
            "ItemIsMenu" to Variant(false),
        )

    override fun getObjectPath() = PATH

    override fun activate(
        x: Int,
        y: Int,
    ) = onActivate()

    override fun secondaryActivate(
        x: Int,
        y: Int,
    ) = onActivate()

    override fun contextMenu(
        x: Int,
        y: Int,
    ) = onActivate()

    override fun scroll(
        delta: Int,
        orientation: String,
    ) = Unit

    /** The stored Variant, which carries signatures such as `a(iiay)` that erased values cannot. */
    @Suppress("UNCHECKED_CAST")
    override fun <A : Any?> Get(
        iface: String,
        name: String,
    ): A = properties.getValue(name) as A

    override fun <A : Any?> Set(
        iface: String,
        name: String,
        value: A,
    ) = throw UnsupportedOperationException("EVA's tray properties are read-only.")

    override fun GetAll(iface: String): Map<String, Variant<*>> = properties

    override fun close() {
        retries.shutdownNow()
        connection.close()
    }

    companion object {
        private const val PATH = "/StatusNotifierItem"
        private const val WATCHER = "org.kde.StatusNotifierWatcher"
        private const val WATCHER_PATH = "/StatusNotifierWatcher"
        private const val FIRST_RETRY_MILLIS = 100L
        private const val LAST_RETRY_MILLIS = 10_000L

        internal fun nextDelay(previous: Long) = if (previous <= 0) FIRST_RETRY_MILLIS else minOf(previous * 2, LAST_RETRY_MILLIS)

        /** Shows the icon, or fails when no panel hosts one, as on a desktop without a StatusNotifierWatcher. */
        fun show(
            onActivate: () -> Unit,
            busAddress: () -> String = ::sessionBusAddress,
        ): Result<StatusNotifier> =
            runCatching {
                val connection = DBusConnectionBuilder.forAddress(busAddress()).withShared(false).build()
                try {
                    val service = "org.kde.StatusNotifierItem-${ProcessHandle.current().pid()}-1"
                    val item = StatusNotifier(connection, service, onActivate)
                    connection.requestBusName(service)
                    connection.exportObject(PATH, item)
                    item.watchOwnership()
                    val owner = runCatching { item.bus.GetNameOwner(WATCHER) }.getOrNull()
                    check(owner != null && item.register(owner)) { "No StatusNotifierWatcher accepted the icon." }
                    item
                } catch (failure: Exception) {
                    connection.close()
                    throw failure
                }
            }

        private fun sessionBusAddress(): String =
            System.getenv("DBUS_SESSION_BUS_ADDRESS")?.takeIf { it.isNotBlank() }
                ?: System.getenv("XDG_RUNTIME_DIR")?.let { "unix:path=$it/bus" }
                ?: error("No D-Bus session bus is available.")

        internal fun pixmap(image: BufferedImage): IconPixmap {
            val pixels = ByteArray(image.width * image.height * 4)
            var offset = 0
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    val argb = image.getRGB(x, y)
                    pixels[offset++] = (argb ushr 24).toByte()
                    pixels[offset++] = (argb ushr 16).toByte()
                    pixels[offset++] = (argb ushr 8).toByte()
                    pixels[offset++] = argb.toByte()
                }
            }
            return IconPixmap(image.width, image.height, pixels)
        }
    }
}
