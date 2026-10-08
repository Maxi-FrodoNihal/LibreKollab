package org.msc.librekollab.adapter.libreoffice.plugin

import com.sun.star.beans.NamedValue
import com.sun.star.comp.loader.FactoryHelper
import com.sun.star.lang.XServiceInfo
import com.sun.star.lang.XSingleComponentFactory
import com.sun.star.task.XJob
import com.sun.star.uno.XComponentContext
import io.ktor.server.engine.EmbeddedServer
import org.msc.librekollab.adapter.logging.LoggingKollabAPI
import org.msc.librekollab.adapter.mcp.McpServer
import org.msc.librekollab.adapter.libreoffice.LibreKollab
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class LibreKollabPlugin(private val context: XComponentContext) : XJob, XServiceInfo {

    init {
        instance = this
    }

    companion object {
        const val SERVICE_NAME = "org.msc.librekollab.LibreKollabPlugin"
        const val IMPLEMENTATION_NAME = "org.msc.librekollab.adapter.libreoffice.plugin.LibreKollabPlugin"

        internal const val PORT_PROPERTY = "librekollab.port"
        internal const val DEFAULT_PORT = 8080

        private val log = LoggerFactory.getLogger(LibreKollabPlugin::class.java)
        private val running = AtomicBoolean(false)
        private var engine: EmbeddedServer<*, *>? = null
        private var instance: LibreKollabPlugin? = null

        fun isRunning(): Boolean = running.get()

        fun start() {
            val inst = instance ?: return
            val port = System.getProperty(PORT_PROPERTY, DEFAULT_PORT.toString()).toInt()
            if (running.compareAndSet(false, true)) {
                log.info("Starting MCP server on port {}", port)
                val kollab = LoggingKollabAPI(LibreKollab(inst.context))
                try {
                    engine = McpServer(kollab).startHttp(port)
                } catch (e: IOException) {
                    running.set(false)
                    throw e
                }
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
        fun __getComponentFactory(implementationName: String): XSingleComponentFactory? {
            log.info("__getComponentFactory called with: {}", implementationName)
            return componentFactoryFor(implementationName)
        }

        @Suppress("UNCHECKED_CAST")
        private fun componentFactoryFor(implementationName: String): XSingleComponentFactory? {
            return when (implementationName) {
                IMPLEMENTATION_NAME ->
                    FactoryHelper.createComponentFactory(LibreKollabPlugin::class.java, IMPLEMENTATION_NAME) as XSingleComponentFactory
                OptionsHandler.IMPLEMENTATION_NAME ->
                    FactoryHelper.createComponentFactory(OptionsHandler::class.java, OptionsHandler.IMPLEMENTATION_NAME) as XSingleComponentFactory
                else -> {
                    log.warn("__getComponentFactory: unknown implementation: {}", implementationName)
                    null
                }
            }
        }
    }

    override fun execute(arguments: Array<out NamedValue>): Any {
        log.info("execute() called - plugin ready")
        return ""
    }

    override fun getImplementationName(): String = IMPLEMENTATION_NAME
    override fun supportsService(name: String): Boolean = name == SERVICE_NAME
    override fun getSupportedServiceNames(): Array<String> = arrayOf(SERVICE_NAME)
}
