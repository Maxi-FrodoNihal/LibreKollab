package org.msc.liberekollab.adapter.libreoffice.plugin

import com.sun.star.beans.NamedValue
import com.sun.star.comp.loader.FactoryHelper
import com.sun.star.lang.XServiceInfo
import com.sun.star.lang.XSingleComponentFactory
import com.sun.star.task.XJob
import com.sun.star.uno.XComponentContext
import io.ktor.server.engine.EmbeddedServer
import org.msc.liberekollab.adapter.logging.LoggingKollabAPI
import org.msc.liberekollab.adapter.mcp.McpServer
import org.msc.liberekollab.adapter.libreoffice.LibereKollab
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

class LibereKollabPlugin(private val context: XComponentContext) : XJob, XServiceInfo {

    init {
        instance = this
    }

    override fun execute(arguments: Array<out NamedValue>): Any {
        log.info("execute() called - plugin ready")
        return ""
    }

    override fun getImplementationName(): String = IMPLEMENTATION_NAME
    override fun supportsService(name: String): Boolean = name == SERVICE_NAME
    override fun getSupportedServiceNames(): Array<String> = arrayOf(SERVICE_NAME)

    companion object {
        const val SERVICE_NAME = "org.msc.liberekollab.LibereKollabPlugin"
        const val IMPLEMENTATION_NAME = "org.msc.liberekollab.adapter.libreoffice.plugin.LibereKollabPlugin"

        private val log = LoggerFactory.getLogger(LibereKollabPlugin::class.java)
        private val running = AtomicBoolean(false)
        private var engine: EmbeddedServer<*, *>? = null
        private var instance: LibereKollabPlugin? = null

        fun isRunning(): Boolean = running.get()

        fun start() {
            val inst = instance ?: return
            if (running.compareAndSet(false, true)) {
                val port = System.getProperty("liberekollab.port", "8080").toInt()
                log.info("Starting MCP server on port {}", port)
                val kollab = LoggingKollabAPI(LibereKollab(inst.context))
                engine = McpServer(kollab).startSse(port)
                log.info("MCP server started on port {}", port)
            }
        }

        fun close() {
            log.info("Stopping MCP server")
            engine?.stop()
            engine = null
            running.set(false)
            log.info("MCP server stopped")
        }

        @JvmStatic
        @Suppress("UNCHECKED_CAST")
        fun __getComponentFactory(implementationName: String): XSingleComponentFactory? {
            log.info("__getComponentFactory called with: {}", implementationName)
            return when (implementationName) {
                IMPLEMENTATION_NAME ->
                    FactoryHelper.createComponentFactory(LibereKollabPlugin::class.java, IMPLEMENTATION_NAME) as XSingleComponentFactory
                OptionsHandler.IMPLEMENTATION_NAME ->
                    FactoryHelper.createComponentFactory(OptionsHandler::class.java, OptionsHandler.IMPLEMENTATION_NAME) as XSingleComponentFactory
                else -> {
                    log.warn("__getComponentFactory: unknown implementation: {}", implementationName)
                    null
                }
            }
        }
    }
}
