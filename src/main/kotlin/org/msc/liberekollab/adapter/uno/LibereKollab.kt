package org.msc.liberekollab.adapter.uno

import com.sun.star.container.XEnumerationAccess
import com.sun.star.frame.XDesktop
import com.sun.star.frame.XModel
import com.sun.star.frame.XStorable
import com.sun.star.lang.XMultiComponentFactory
import com.sun.star.text.XTextDocument
import com.sun.star.uno.UnoRuntime
import com.sun.star.uno.XComponentContext
import kotlinx.coroutines.withContext

class LibereKollab(private val context: XComponentContext) : CoreKollab() {

    private fun getDesktop(): XDesktop {
        val serviceManager = UnoRuntime.queryInterface(XMultiComponentFactory::class.java, context.serviceManager)
        return UnoRuntime.queryInterface(
            XDesktop::class.java,
            serviceManager.createInstanceWithContext("com.sun.star.frame.Desktop", context)
        )
    }

    private fun findDocument(documentId: String): XTextDocument {
        val components = UnoRuntime.queryInterface(XEnumerationAccess::class.java, getDesktop().components)
            .createEnumeration()
        while (components.hasMoreElements()) {
            val doc = UnoRuntime.queryInterface(XTextDocument::class.java, components.nextElement())
                ?: continue
            val url = UnoRuntime.queryInterface(XModel::class.java, doc)?.getURL() ?: continue
            if (url.endsWith("/$documentId") || url == documentId) return doc
        }
        throw NoSuchElementException("No open document found for: $documentId")
    }

    override suspend fun <T> withDocument(documentId: String, block: (XTextDocument) -> T): T =
        withContext(libreOfficeDispatcher) { block(findDocument(documentId)) }

    override suspend fun <T> withDocumentMutating(documentId: String, block: (XTextDocument) -> T): T =
        withContext(libreOfficeDispatcher) {
            val doc = findDocument(documentId)
            val result = block(doc)
            UnoRuntime.queryInterface(XStorable::class.java, doc)?.store()
            result
        }
}
