package org.msc.librekollab.adapter.logging

import ch.qos.logback.core.PropertyDefinerBase

class LogDirResolver : PropertyDefinerBase() {
    override fun getPropertyValue(): String {
        val logDir: String
        if (isWindows()) {
            val appData = System.getenv("APPDATA") ?: "${System.getProperty("user.home")}\\AppData\\Roaming"
            logDir = "$appData\\librekollab"
        } else {
            logDir = "${System.getProperty("user.home")}/.config/librekollab"
        }
        return logDir
    }

    private fun isWindows(): Boolean {
        val os = System.getProperty("os.name", "").lowercase()
        return os.contains("win")
    }
}
