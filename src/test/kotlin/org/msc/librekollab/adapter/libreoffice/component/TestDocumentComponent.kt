package org.msc.librekollab.adapter.libreoffice.component

import com.sun.star.beans.PropertyValue
import com.sun.star.frame.XComponentLoader
import com.sun.star.frame.XStorable
import com.sun.star.lang.XComponent
import com.sun.star.lang.XMultiComponentFactory
import com.sun.star.text.XTextDocument
import com.sun.star.uno.UnoRuntime
import com.sun.star.uno.XComponentContext
import java.io.File

class TestDocumentComponent(
    private val componentContext: XComponentContext,
    private val basePath: String,
    private val containerWorkspacePath: String
) : DocumentComponent {

    companion object {
        private const val DESKTOP_SERVICE = "com.sun.star.frame.Desktop"
        private const val HIDDEN_PROPERTY = "Hidden"
        private const val NEW_FRAME_TARGET = "_blank"
    }

    override suspend fun listDocuments(): List<String> =
        File(basePath).listFiles()?.map { it.name } ?: emptyList()

    override suspend fun <T> withDocument(documentId: String, block: (XTextDocument) -> T): T =
        withLoadedDocument(documentId, store = false, block)

    override suspend fun <T> withDocumentMutating(documentId: String, block: (XTextDocument) -> T): T =
        withLoadedDocument(documentId, store = true, block)

    private fun getDesktop(): XComponentLoader {
        val serviceManager = UnoRuntime.queryInterface(XMultiComponentFactory::class.java, componentContext.serviceManager)
        return UnoRuntime.queryInterface(
            XComponentLoader::class.java,
            serviceManager.createInstanceWithContext(DESKTOP_SERVICE, componentContext)
        )
    }

    private fun <T> withLoadedDocument(documentId: String, store: Boolean, block: (XTextDocument) -> T): T {
        val loadProps = arrayOf(PropertyValue().apply { Name = HIDDEN_PROPERTY; Value = true })
        val component = getDesktop().loadComponentFromURL(
            "file://$containerWorkspacePath/$documentId", NEW_FRAME_TARGET, 0, loadProps
        )
        val textDoc = UnoRuntime.queryInterface(XTextDocument::class.java, component)
        return try {
            val result = block(textDoc)
            if (store) {
                UnoRuntime.queryInterface(XStorable::class.java, component).store()
            }
            result
        } finally {
            UnoRuntime.queryInterface(XComponent::class.java, component)?.dispose()
        }
    }
}
