package org.msc.liberekollab.adapter.libreoffice.plugin

import com.sun.star.awt.XButton
import com.sun.star.awt.XContainerWindowEventHandler
import com.sun.star.awt.XControl
import com.sun.star.awt.XControlContainer
import com.sun.star.awt.XFixedText
import com.sun.star.awt.XTextComponent
import com.sun.star.awt.XWindow
import com.sun.star.beans.XPropertySet
import com.sun.star.comp.loader.FactoryHelper
import com.sun.star.lang.XServiceInfo
import com.sun.star.lang.XSingleComponentFactory
import com.sun.star.lib.uno.helper.WeakBase
import com.sun.star.uno.AnyConverter
import com.sun.star.uno.UnoRuntime
import com.sun.star.uno.XComponentContext
import org.msc.liberekollab.adapter.logging.LogDirResolver
import org.slf4j.LoggerFactory
import java.awt.Desktop
import java.io.File

class OptionsHandler(private val context: XComponentContext) :
    WeakBase(), XContainerWindowEventHandler, XServiceInfo {

    private var container: XControlContainer? = null

    override fun callHandlerMethod(window: XWindow, eventObject: Any, method: String): Boolean {
        log.info("callHandlerMethod: {}", method)
        return when (method) {
            "external_event" -> handleExternalEvent(window, eventObject)
            "toggle" -> { toggle(); true }
            "openLog" -> { openLogFile(); true }
            else -> false
        }
    }

    override fun getSupportedMethodNames(): Array<String> = arrayOf("external_event", "toggle", "openLog")

    private fun handleExternalEvent(window: XWindow, eventObject: Any): Boolean {
        val event = runCatching { AnyConverter.toString(eventObject) }.getOrNull() ?: return false
        log.info("external_event: {}", event)
        container = UnoRuntime.queryInterface(XControlContainer::class.java, window)
        return when (event) {
            "initialize", "back" -> { initialize(); true }
            "ok" -> { savePort(); true }
            else -> false
        }
    }

    private fun initialize() {
        val c = container ?: return
        val statusModel = UnoRuntime.queryInterface(XPropertySet::class.java,
            UnoRuntime.queryInterface(XControl::class.java, c.getControl("lblStatus")).getModel())
        statusModel.setPropertyValue("MultiLine", true)
        updateUI(c)
    }

    private fun toggle() {
        val c = container ?: return
        val port = text(c, "txtPort").getText().trim()
        if (port.isNotEmpty()) System.setProperty("liberekollab.port", port)
        if (LibereKollabPlugin.isRunning()) LibereKollabPlugin.close()
        else LibereKollabPlugin.start()
        updateUI(c)
    }

    private fun savePort() {
        val port = text(container ?: return, "txtPort").getText().trim()
        if (port.isNotEmpty()) System.setProperty("liberekollab.port", port)
    }

    private fun updateUI(c: XControlContainer) {
        val isRunning = LibereKollabPlugin.isRunning()
        label(c, "lblStatus").setText(
            if (isRunning) "Status:\nserver is running on port ${currentPort()}"
            else "Status:\nserver is stopped"
        )
        text(c, "txtPort").setText(currentPort())
        button(c, "btnToggle").setLabel(if (isRunning) "Stop server" else "Start server")
    }

    private fun openLogFile() {
        val logDir = LogDirResolver().getPropertyValue()
        val logFile = File(logDir, "liberekollab.log")
        val target = if (logFile.exists()) logFile else File(logDir).also { it.mkdirs() }
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(target)
    }

    private fun currentPort() = System.getProperty("liberekollab.port", "8080")
    private fun label(c: XControlContainer, id: String) = UnoRuntime.queryInterface(XFixedText::class.java, c.getControl(id))
    private fun text(c: XControlContainer, id: String) = UnoRuntime.queryInterface(XTextComponent::class.java, c.getControl(id))
    private fun button(c: XControlContainer, id: String) = UnoRuntime.queryInterface(XButton::class.java, c.getControl(id))

    override fun getImplementationName(): String = IMPLEMENTATION_NAME
    override fun supportsService(name: String): Boolean = name == SERVICE_NAME
    override fun getSupportedServiceNames(): Array<String> = arrayOf(SERVICE_NAME)

    companion object {
        const val SERVICE_NAME = "org.msc.liberekollab.OptionsHandler"
        const val IMPLEMENTATION_NAME = "org.msc.liberekollab.adapter.libreoffice.plugin.OptionsHandler"
        private val log = LoggerFactory.getLogger(OptionsHandler::class.java)

        @JvmStatic
        @Suppress("UNCHECKED_CAST")
        fun __getComponentFactory(implementationName: String): XSingleComponentFactory? =
            if (implementationName == IMPLEMENTATION_NAME)
                FactoryHelper.createComponentFactory(OptionsHandler::class.java, IMPLEMENTATION_NAME) as XSingleComponentFactory
            else null
    }
}
