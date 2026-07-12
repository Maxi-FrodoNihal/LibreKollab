package org.msc.librekollab.adapter.libreoffice.plugin

import com.sun.star.awt.XButton
import com.sun.star.awt.XContainerWindowEventHandler
import com.sun.star.awt.XControl
import com.sun.star.awt.XControlContainer
import com.sun.star.awt.XFixedText
import com.sun.star.awt.XTextComponent
import com.sun.star.awt.XWindow
import com.sun.star.beans.XPropertySet
import com.sun.star.comp.loader.FactoryHelper
import com.sun.star.lang.IllegalArgumentException
import com.sun.star.lang.XServiceInfo
import com.sun.star.lang.XSingleComponentFactory
import com.sun.star.lib.uno.helper.WeakBase
import com.sun.star.uno.AnyConverter
import com.sun.star.uno.UnoRuntime
import com.sun.star.uno.XComponentContext
import org.msc.librekollab.adapter.logging.LogDirResolver
import org.slf4j.LoggerFactory
import java.awt.Desktop
import java.io.File

class OptionsHandler(private val context: XComponentContext) :
    WeakBase(), XContainerWindowEventHandler, XServiceInfo {

    private var container: XControlContainer? = null

    companion object {
        const val SERVICE_NAME = "org.msc.librekollab.OptionsHandler"
        const val IMPLEMENTATION_NAME = "org.msc.librekollab.adapter.libreoffice.plugin.OptionsHandler"

        private const val METHOD_EXTERNAL_EVENT = "external_event"
        private const val METHOD_TOGGLE = "toggle"
        private const val METHOD_OPEN_LOG = "openLog"

        private const val CONTROL_STATUS_LABEL = "lblStatus"
        private const val CONTROL_PORT_TEXT = "txtPort"
        private const val CONTROL_TOGGLE_BUTTON = "btnToggle"

        private const val EVENT_INITIALIZE = "initialize"
        private const val EVENT_BACK = "back"
        private const val EVENT_OK = "ok"

        private const val MULTI_LINE_PROPERTY = "MultiLine"

        private val log = LoggerFactory.getLogger(OptionsHandler::class.java)

        @JvmStatic
        @Suppress("UNCHECKED_CAST")
        fun __getComponentFactory(implementationName: String): XSingleComponentFactory? {
            if (implementationName == IMPLEMENTATION_NAME) {
                return FactoryHelper.createComponentFactory(OptionsHandler::class.java, IMPLEMENTATION_NAME) as XSingleComponentFactory
            }
            return null
        }
    }

    override fun callHandlerMethod(window: XWindow, eventObject: Any, method: String): Boolean {
        log.info("callHandlerMethod: {}", method)
        return when (method) {
            METHOD_EXTERNAL_EVENT -> handleExternalEvent(window, eventObject)
            METHOD_TOGGLE -> { toggle(); true }
            METHOD_OPEN_LOG -> { openLogFile(); true }
            else -> false
        }
    }

    override fun getSupportedMethodNames(): Array<String> = arrayOf(METHOD_EXTERNAL_EVENT, METHOD_TOGGLE, METHOD_OPEN_LOG)

    override fun getImplementationName(): String = IMPLEMENTATION_NAME
    override fun supportsService(name: String): Boolean = name == SERVICE_NAME
    override fun getSupportedServiceNames(): Array<String> = arrayOf(SERVICE_NAME)

    private fun handleExternalEvent(window: XWindow, eventObject: Any): Boolean {
        val event: String
        try {
            event = AnyConverter.toString(eventObject)
        } catch (e: IllegalArgumentException) {
            return false
        }
        log.info("external_event: {}", event)
        container = UnoRuntime.queryInterface(XControlContainer::class.java, window)
        return when (event) {
            EVENT_INITIALIZE, EVENT_BACK -> { initialize(); true }
            EVENT_OK -> { savePort(); true }
            else -> false
        }
    }

    private fun initialize() {
        val c = container ?: return
        val statusModel = UnoRuntime.queryInterface(XPropertySet::class.java,
            UnoRuntime.queryInterface(XControl::class.java, c.getControl(CONTROL_STATUS_LABEL)).getModel())
        statusModel.setPropertyValue(MULTI_LINE_PROPERTY, true)
        updateUI(c)
    }

    private fun toggle() {
        val c = container ?: return
        savePort()
        if (LibreKollabPlugin.isRunning()) {
            LibreKollabPlugin.close()
        } else {
            LibreKollabPlugin.start()
        }
        updateUI(c)
    }

    private fun savePort() {
        val port = text(container ?: return, CONTROL_PORT_TEXT).getText().trim()
        if (port.isEmpty()) {
            return
        }
        if (port.toIntOrNull() == null) {
            log.warn("Ignoring invalid port value: {}", port)
            return
        }
        System.setProperty(LibreKollabPlugin.PORT_PROPERTY, port)
    }

    private fun updateUI(c: XControlContainer) {
        val isRunning = LibreKollabPlugin.isRunning()
        val statusText = if (isRunning) {
            "Status:\nserver is running on port ${currentPort()}"
        } else {
            "Status:\nserver is stopped"
        }
        val toggleLabel = if (isRunning) {
            "Stop server"
        } else {
            "Start server"
        }
        label(c, CONTROL_STATUS_LABEL).setText(statusText)
        text(c, CONTROL_PORT_TEXT).setText(currentPort())
        button(c, CONTROL_TOGGLE_BUTTON).setLabel(toggleLabel)
    }

    private fun openLogFile() {
        val logDir = LogDirResolver().getPropertyValue()
        val logFile = File(logDir, "librekollab.log")
        val target: File
        if (logFile.exists()) {
            target = logFile
        } else {
            target = File(logDir).also { it.mkdirs() }
        }
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().open(target)
        }
    }

    private fun currentPort() = System.getProperty(LibreKollabPlugin.PORT_PROPERTY, LibreKollabPlugin.DEFAULT_PORT.toString())
    private fun label(c: XControlContainer, id: String) = UnoRuntime.queryInterface(XFixedText::class.java, c.getControl(id))
    private fun text(c: XControlContainer, id: String) = UnoRuntime.queryInterface(XTextComponent::class.java, c.getControl(id))
    private fun button(c: XControlContainer, id: String) = UnoRuntime.queryInterface(XButton::class.java, c.getControl(id))
}
