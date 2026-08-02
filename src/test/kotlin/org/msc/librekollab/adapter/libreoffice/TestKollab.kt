package org.msc.librekollab.adapter.libreoffice

import com.sun.star.bridge.XBridgeFactory
import com.sun.star.bridge.XUnoUrlResolver
import com.sun.star.comp.helper.Bootstrap
import com.sun.star.lang.XComponent
import com.sun.star.uno.UnoRuntime
import com.sun.star.uno.XComponentContext
import org.msc.librekollab.adapter.libreoffice.component.DocumentComponent
import org.msc.librekollab.adapter.libreoffice.component.PersistentTestDocumentComponent
import org.msc.librekollab.adapter.libreoffice.component.TestDocumentComponent

class TestKollab(
    context: XComponentContext,
    documentComponent: DocumentComponent,
    private val onClose: () -> Unit = {}
) : LibreKollab(context, documentComponent = documentComponent), AutoCloseable {

    companion object {
        private const val URL_RESOLVER_SERVICE = "com.sun.star.bridge.UnoUrlResolver"
        private const val BRIDGE_FACTORY_SERVICE = "com.sun.star.bridge.BridgeFactory"
        private const val UNO_CONNECTION_URL_FORMAT = "uno:socket,host=%s,port=%d;urp;StarOffice.ComponentContext"

        // Resolves the remote UNO component context over the socket bridge, and returns a closure that
        // disposes that bridge — shared by both viaSocket (fresh-per-call TestDocumentComponent) and
        // viaSocketPersistent (long-lived PersistentTestDocumentComponent), which differ only in which
        // DocumentComponent they wire up on top of the same connection dance.
        private fun connect(host: String, port: Int): Pair<XComponentContext, () -> Unit> {
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
            val disposeBridge = { UnoRuntime.queryInterface(XComponent::class.java, bridge)?.dispose() ?: Unit }
            return remoteContext to disposeBridge
        }

        fun viaSocket(host: String, port: Int, basePath: String, containerWorkspacePath: String): TestKollab {
            val (remoteContext, disposeBridge) = connect(host, port)
            return TestKollab(remoteContext, TestDocumentComponent(remoteContext, basePath, containerWorkspacePath), disposeBridge)
        }

        // Same connection, but keeps every loaded document open across calls instead of reloading it
        // fresh each time — see PersistentTestDocumentComponent for why that distinction matters.
        fun viaSocketPersistent(host: String, port: Int, basePath: String, containerWorkspacePath: String): TestKollab {
            val (remoteContext, disposeBridge) = connect(host, port)
            val persistentDocumentComponent = PersistentTestDocumentComponent(remoteContext, basePath, containerWorkspacePath)
            return TestKollab(remoteContext, persistentDocumentComponent) {
                persistentDocumentComponent.close()
                disposeBridge()
            }
        }
    }

    override fun close() = onClose()
}
