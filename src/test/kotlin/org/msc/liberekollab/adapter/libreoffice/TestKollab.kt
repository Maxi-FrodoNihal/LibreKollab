package org.msc.liberekollab.adapter.libreoffice

import com.sun.star.beans.PropertyValue
import com.sun.star.bridge.XBridgeFactory
import com.sun.star.bridge.XUnoUrlResolver
import com.sun.star.comp.helper.Bootstrap
import com.sun.star.frame.XComponentLoader
import com.sun.star.frame.XStorable
import com.sun.star.lang.XComponent
import com.sun.star.lang.XMultiComponentFactory
import com.sun.star.text.XTextDocument
import com.sun.star.uno.UnoRuntime
import com.sun.star.uno.XComponentContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class TestKollab(
    private val context: XComponentContext,
    private val basePath: String,
    private val containerWorkspacePath: String,
    private val onClose: () -> Unit = {}
) : CoreKollab() {

    companion object {
        fun viaSocket(host: String, port: Int, basePath: String, containerWorkspacePath: String): TestKollab {
            val localContext = Bootstrap.createInitialComponentContext(null)
            val urlResolver = UnoRuntime.queryInterface(
                XUnoUrlResolver::class.java,
                localContext.serviceManager.createInstanceWithContext(
                    "com.sun.star.bridge.UnoUrlResolver", localContext
                )
            )
            val remoteInterface = urlResolver.resolve("uno:socket,host=$host,port=$port;urp;StarOffice.ComponentContext")
            val bridge = UnoRuntime.queryInterface(
                XBridgeFactory::class.java,
                localContext.serviceManager.createInstanceWithContext("com.sun.star.bridge.BridgeFactory", localContext)
            )?.existingBridges?.firstOrNull()
            val remoteContext = UnoRuntime.queryInterface(XComponentContext::class.java, remoteInterface)
            return TestKollab(remoteContext, basePath, containerWorkspacePath) {
                bridge?.let { UnoRuntime.queryInterface(XComponent::class.java, it)?.dispose() }
            }
        }
    }

    fun close() = onClose()

    override suspend fun listDocuments(): List<String> = withContext(Dispatchers.IO) {
        File(basePath).listFiles()?.map { it.name } ?: emptyList()
    }

    private fun getDesktop(): XComponentLoader {
        val serviceManager = UnoRuntime.queryInterface(XMultiComponentFactory::class.java, context.serviceManager)
        return UnoRuntime.queryInterface(
            XComponentLoader::class.java,
            serviceManager.createInstanceWithContext("com.sun.star.frame.Desktop", context)
        )
    }

    override suspend fun <T> withDocument(documentId: String, block: (XTextDocument) -> T): T {
        val loadProps = arrayOf(PropertyValue().apply { Name = "Hidden"; Value = true })
        val component = getDesktop().loadComponentFromURL(
            "file://$containerWorkspacePath/$documentId", "_blank", 0, loadProps
        )
        val textDoc = UnoRuntime.queryInterface(XTextDocument::class.java, component)
        return try {
            block(textDoc)
        } finally {
            UnoRuntime.queryInterface(XComponent::class.java, component)?.dispose()
        }
    }

    override suspend fun <T> withDocumentMutating(documentId: String, block: (XTextDocument) -> T): T {
        val loadProps = arrayOf(PropertyValue().apply { Name = "Hidden"; Value = true })
        val component = getDesktop().loadComponentFromURL(
            "file://$containerWorkspacePath/$documentId", "_blank", 0, loadProps
        )
        val textDoc = UnoRuntime.queryInterface(XTextDocument::class.java, component)
        return try {
            val result = block(textDoc)
            UnoRuntime.queryInterface(XStorable::class.java, component).store()
            result
        } finally {
            UnoRuntime.queryInterface(XComponent::class.java, component)?.dispose()
        }
    }
}
