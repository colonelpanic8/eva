package com.colonelpanic.eva.desktop

import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/** EVA's icon in the desktop panel; clicking it toggles the window. */
interface PanelIcon : AutoCloseable {
    /** Whether a panel shows the icon now; while none does, closing the window must not hide EVA. */
    fun visible(): Boolean

    companion object {
        /**
         * A StatusNotifierItem where a panel hosts them (Wayland, KDE, most current bars), else the
         * X11 system tray, else null with the reasons neither was available.
         */
        fun show(onActivate: () -> Unit): Pair<PanelIcon?, List<String>> {
            val problems = mutableListOf<String>()
            StatusNotifier.show(onActivate).onSuccess { return it to problems }.onFailure { problems += "StatusNotifier: ${it.message}" }
            AwtTrayIcon.show(onActivate).onSuccess { return it to problems }.onFailure { problems += "X11 tray: ${it.message}" }
            return null to problems
        }

        internal fun image(): BufferedImage =
            checkNotNull(PanelIcon::class.java.getResourceAsStream("/eva-icon.png")) { "The tray icon is missing." }.use(ImageIO::read)
    }
}

/** The AWT system tray, which X11 panels host through the XEmbed protocol. */
private class AwtTrayIcon(
    private val icon: TrayIcon,
) : PanelIcon {
    override fun visible() = true

    override fun close() = SystemTray.getSystemTray().remove(icon)

    companion object {
        fun show(onActivate: () -> Unit): Result<PanelIcon> =
            runCatching {
                check(SystemTray.isSupported()) { "this desktop has none" }
                val icon =
                    TrayIcon(PanelIcon.image(), "EVA").apply {
                        isImageAutoSize = true
                        addMouseListener(
                            object : MouseAdapter() {
                                override fun mouseClicked(event: MouseEvent) = onActivate()
                            },
                        )
                    }
                SystemTray.getSystemTray().add(icon)
                AwtTrayIcon(icon)
            }
    }
}
