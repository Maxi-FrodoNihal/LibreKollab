package org.msc.librekollab.adapter.libreoffice

import com.sun.star.beans.PropertyValue
import com.sun.star.beans.XPropertySet
import com.sun.star.bridge.XBridgeFactory
import com.sun.star.bridge.XUnoUrlResolver
import com.sun.star.comp.helper.Bootstrap
import com.sun.star.frame.XComponentLoader
import com.sun.star.frame.XStorable
import com.sun.star.lang.XComponent
import com.sun.star.lang.XMultiComponentFactory
import com.sun.star.lang.XMultiServiceFactory
import com.sun.star.text.XTextDocument
import com.sun.star.uno.UnoRuntime
import com.sun.star.uno.XComponentContext
import com.sun.star.util.XChangesBatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class TestKollab(
    context: XComponentContext,
    private val basePath: String,
    private val containerWorkspacePath: String,
    private val onClose: () -> Unit = {}
) : CoreKollab(context) {

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

    override fun withAuthor(author: String, block: () -> Unit) {
        if (author.isEmpty()) { block(); return }
        val configProvider = UnoRuntime.queryInterface(
            XMultiServiceFactory::class.java,
            componentContext.serviceManager.createInstanceWithContext("com.sun.star.configuration.ConfigurationProvider", componentContext)
        )
        val nodeArg = PropertyValue().apply { Name = "nodepath"; Value = "/org.openoffice.UserProfile/Data" }
        val access = UnoRuntime.queryInterface(
            XPropertySet::class.java,
            configProvider.createInstanceWithArguments("com.sun.star.configuration.ConfigurationUpdateAccess", arrayOf(nodeArg))
        )
        val oldFirst = access.getPropertyValue("givenname") as String
        val oldLast = access.getPropertyValue("sn") as String
        try {
            access.setPropertyValue("givenname", author)
            access.setPropertyValue("sn", "")
            UnoRuntime.queryInterface(XChangesBatch::class.java, access).commitChanges()
            block()
        } finally {
            access.setPropertyValue("givenname", oldFirst)
            access.setPropertyValue("sn", oldLast)
            UnoRuntime.queryInterface(XChangesBatch::class.java, access).commitChanges()
        }
    }

    override suspend fun listDocuments(): List<String> = withContext(Dispatchers.IO) {
        File(basePath).listFiles()?.map { it.name } ?: emptyList()
    }

    private fun getDesktop(): XComponentLoader {
        val serviceManager = UnoRuntime.queryInterface(XMultiComponentFactory::class.java, componentContext.serviceManager)
        return UnoRuntime.queryInterface(
            XComponentLoader::class.java,
            serviceManager.createInstanceWithContext("com.sun.star.frame.Desktop", componentContext)
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
