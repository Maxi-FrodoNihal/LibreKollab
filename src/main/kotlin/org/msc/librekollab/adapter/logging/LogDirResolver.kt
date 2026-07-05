package org.msc.librekollab.adapter.logging

import ch.qos.logback.core.PropertyDefinerBase

class LogDirResolver : PropertyDefinerBase() {
    override fun getPropertyValue(): String {
        val os = System.getProperty("os.name", "").lowercase()
        return if (os.contains("win")) {
            "${System.getenv("APPDATA")}\\librekollab"
        } else {
            "${System.getProperty("user.home")}/.config/librekollab"
        }
    }
}
