package org.msc.liberekollab

import ch.qos.logback.core.PropertyDefinerBase

class LogDirResolver : PropertyDefinerBase() {
    override fun getPropertyValue(): String {
        val os = System.getProperty("os.name", "").lowercase()
        return if (os.contains("win")) {
            "${System.getenv("APPDATA")}\\liberekollab"
        } else {
            "${System.getProperty("user.home")}/.config/liberekollab"
        }
    }
}
