package org.msc.liberekollab.adapter.libreoffice.plugin

import com.sun.star.awt.ActionEvent
import com.sun.star.awt.XActionListener
import com.sun.star.awt.XButton
import com.sun.star.awt.XControlContainer
import com.sun.star.awt.XDialog
import com.sun.star.awt.XControl
import com.sun.star.awt.XFixedText
import com.sun.star.awt.XTextComponent
import com.sun.star.beans.XPropertySet
import com.sun.star.lang.EventObject
import com.sun.star.uno.UnoRuntime
import org.msc.liberekollab.adapter.logging.LogDirResolver
import org.slf4j.LoggerFactory
import java.awt.Desktop
import java.io.File

class OptionsHandler {

    fun initializeDialog(dialog: XDialog) {
        val container = UnoRuntime.queryInterface(XControlContainer::class.java, dialog)
        val statusModel = UnoRuntime.queryInterface(XPropertySet::class.java,
            UnoRuntime.queryInterface(XControl::class.java, container.getControl("lblStatus")).getModel())
        statusModel.setPropertyValue("MultiLine", true)
        updateUI(container)
        button(container, "btnToggle").addActionListener(object : XActionListener {
            override fun actionPerformed(e: ActionEvent) {
                val port = text(container, "txtPort").getText().trim()
                if (port.isNotEmpty()) System.setProperty("liberekollab.port", port)
                if (LibereKollabPlugin.isRunning()) LibereKollabPlugin.close()
                else LibereKollabPlugin.start()
                updateUI(container)
            }
            override fun disposing(e: EventObject) {}
        })
        button(container, "btnOpenLog").addActionListener(object : XActionListener {
            override fun actionPerformed(e: ActionEvent) = openLogFile()
            override fun disposing(e: EventObject) {}
        })
    }

    private fun updateUI(container: XControlContainer) {
        val isRunning = LibereKollabPlugin.isRunning()
        label(container, "lblStatus").setText(
            if (isRunning) "Status:\nserver is running on port ${currentPort()}"
            else "Status:\nserver is stopped"
        )
        text(container, "txtPort").setText(currentPort())
        button(container, "btnToggle").setLabel(if (isRunning) "Stop server" else "Start server")
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

    companion object {
        private val log = LoggerFactory.getLogger(OptionsHandler::class.java)
    }
}
