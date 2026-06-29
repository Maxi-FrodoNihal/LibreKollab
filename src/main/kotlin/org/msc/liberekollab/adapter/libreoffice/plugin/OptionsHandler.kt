package org.msc.liberekollab.adapter.libreoffice.plugin

import com.sun.star.awt.ActionEvent
import com.sun.star.awt.XActionListener
import com.sun.star.awt.XButton
import com.sun.star.awt.XControlContainer
import com.sun.star.awt.XFixedText
import com.sun.star.awt.XTextComponent
import com.sun.star.awt.XWindow
import com.sun.star.lang.EventObject
import com.sun.star.lang.XServiceInfo
import com.sun.star.uno.UnoRuntime
import com.sun.star.uno.XComponentContext
import org.msc.liberekollab.adapter.logging.LogDirResolver
import java.awt.Desktop
import java.io.File

class OptionsHandler(private val context: XComponentContext) :
    com.sun.star.awt.XContainerWindowEventHandler, XServiceInfo {

    override fun callHandlerMethod(window: XWindow, eventObject: Any, method: String): Boolean {
        val container = UnoRuntime.queryInterface(XControlContainer::class.java, window)
        return when (method) {
            "initialize" -> { initialize(container); true }
            "ok"         -> { savePort(container); true }
            else         -> false
        }
    }

    override fun getSupportedMethodNames(): Array<String> = arrayOf("initialize", "ok")

    private fun initialize(container: XControlContainer) {
        val isRunning = LibereKollabPlugin.isRunning()
        label(container, "lblStatus").setText(
            if (isRunning) "Status: läuft auf Port ${currentPort()}" else "Status: gestoppt"
        )
        text(container, "txtPort").setText(currentPort())
        button(container, "btnToggle").apply {
            setLabel(if (isRunning) "Server stoppen" else "Server starten")
            addActionListener(object : XActionListener {
                override fun actionPerformed(e: ActionEvent) {
                    if (LibereKollabPlugin.isRunning()) LibereKollabPlugin.close()
                    else LibereKollabPlugin.start()
                    initialize(container)
                }
                override fun disposing(e: EventObject) {}
            })
        }
        button(container, "btnOpenLog").addActionListener(object : XActionListener {
            override fun actionPerformed(e: ActionEvent) = openLogFile()
            override fun disposing(e: EventObject) {}
        })
    }

    private fun openLogFile() {
        val logDir = LogDirResolver().getPropertyValue()
        val logFile = File(logDir, "liberekollab.log")
        val target = if (logFile.exists()) logFile else File(logDir).also { it.mkdirs() }
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(target)
    }

    private fun savePort(container: XControlContainer) {
        val port = text(container, "txtPort").getText().trim()
        if (port.isNotEmpty()) System.setProperty("liberekollab.port", port)
    }

    private fun currentPort() = System.getProperty("liberekollab.port", "8080")

    private fun label(c: XControlContainer, id: String) =
        UnoRuntime.queryInterface(XFixedText::class.java, c.getControl(id))

    private fun text(c: XControlContainer, id: String) =
        UnoRuntime.queryInterface(XTextComponent::class.java, c.getControl(id))

    private fun button(c: XControlContainer, id: String) =
        UnoRuntime.queryInterface(XButton::class.java, c.getControl(id))

    override fun getImplementationName(): String = IMPLEMENTATION_NAME
    override fun supportsService(name: String): Boolean = name == SERVICE_NAME
    override fun getSupportedServiceNames(): Array<String> = arrayOf(SERVICE_NAME)

    companion object {
        const val SERVICE_NAME = "org.msc.liberekollab.OptionsHandler"
        const val IMPLEMENTATION_NAME = "org.msc.liberekollab.adapter.libreoffice.plugin.OptionsHandler"

        @JvmStatic
        fun __create(context: XComponentContext): OptionsHandler = OptionsHandler(context)
    }
}
