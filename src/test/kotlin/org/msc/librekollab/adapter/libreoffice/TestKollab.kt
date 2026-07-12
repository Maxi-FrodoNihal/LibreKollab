package org.msc.librekollab.adapter.libreoffice

import com.sun.star.bridge.XBridgeFactory
import com.sun.star.bridge.XUnoUrlResolver
import com.sun.star.comp.helper.Bootstrap
import com.sun.star.lang.XComponent
import com.sun.star.uno.UnoRuntime
import com.sun.star.uno.XComponentContext
import org.msc.librekollab.adapter.libreoffice.component.TestDocumentComponent

class TestKollab(
    context: XComponentContext,
    basePath: String,
    containerWorkspacePath: String,
    private val onClose: () -> Unit = {}
) : LibreKollab(context, documentComponent = TestDocumentComponent(context, basePath, containerWorkspacePath)), AutoCloseable {

    companion object {
        private const val URL_RESOLVER_SERVICE = "com.sun.star.bridge.UnoUrlResolver"
        private const val BRIDGE_FACTORY_SERVICE = "com.sun.star.bridge.BridgeFactory"
        private const val UNO_CONNECTION_URL_FORMAT = "uno:socket,host=%s,port=%d;urp;StarOffice.ComponentContext"

        fun viaSocket(host: String, port: Int, basePath: String, containerWorkspacePath: String): TestKollab {
            val localContext = Bootstrap.createInitialComponentContext(null)
            val urlResolver = UnoRuntime.queryInterface(
                XUnoUrlResolver::class.java,
                localContext.serviceManager.createInstanceWithContext(URL_RESOLVER_SERVICE, localContext)
            )
            val remoteInterface = urlResolver.resolve(UNO_CONNECTION_URL_FORMAT.format(host, port))
            val bridge = requireNotNull(
                UnoRuntime.queryInterface(
                    XBridgeFactory::class.java,
                    localContext.serviceManager.createInstanceWithContext(BRIDGE_FACTORY_SERVICE, localContext)
                )?.existingBridges?.firstOrNull()
            ) { "No UNO bridge found after resolving $host:$port — nothing to dispose on close()" }
            val remoteContext = UnoRuntime.queryInterface(XComponentContext::class.java, remoteInterface)
            return TestKollab(remoteContext, basePath, containerWorkspacePath) {
                UnoRuntime.queryInterface(XComponent::class.java, bridge)?.dispose()
            }
        }
    }

    override fun close() = onClose()
}
