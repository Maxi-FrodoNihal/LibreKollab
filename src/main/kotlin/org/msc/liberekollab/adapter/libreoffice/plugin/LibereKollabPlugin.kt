package org.msc.liberekollab.adapter.libreoffice.plugin

import com.sun.star.beans.NamedValue
import com.sun.star.lang.XServiceInfo
import com.sun.star.task.XJob
import com.sun.star.uno.XComponentContext
import io.ktor.server.engine.EmbeddedServer
import org.msc.liberekollab.adapter.logging.LoggingKollabAPI
import org.msc.liberekollab.adapter.mcp.McpServer
import org.msc.liberekollab.adapter.libreoffice.LibereKollab
import java.util.concurrent.atomic.AtomicBoolean

class LibereKollabPlugin(private val context: XComponentContext) : XJob, XServiceInfo {

    override fun execute(arguments: Array<out NamedValue>): Any {
        if (running.compareAndSet(false, true)) {
            val port = System.getProperty("liberekollab.port", "8080").toInt()
            val kollab = LoggingKollabAPI(LibereKollab(context))
            engine = McpServer(kollab).startSse(port)
        }
        return ""
    }

    override fun getImplementationName(): String = IMPLEMENTATION_NAME
    override fun supportsService(name: String): Boolean = name == SERVICE_NAME
    override fun getSupportedServiceNames(): Array<String> = arrayOf(SERVICE_NAME)

    companion object {
        const val SERVICE_NAME = "org.msc.liberekollab.LibereKollabPlugin"
        const val IMPLEMENTATION_NAME = "org.msc.liberekollab.adapter.libreoffice.plugin.LibereKollabPlugin"

        private val running = AtomicBoolean(false)
        private var engine: EmbeddedServer<*, *>? = null
        private var instance: LibereKollabPlugin? = null

        fun isRunning(): Boolean = running.get()

        fun start() { instance?.execute(emptyArray()) }

        fun close() {
            engine?.stop()
            engine = null
            running.set(false)
        }

        @JvmStatic
        fun __create(context: XComponentContext): LibereKollabPlugin =
            LibereKollabPlugin(context).also { instance = it }
    }
}
