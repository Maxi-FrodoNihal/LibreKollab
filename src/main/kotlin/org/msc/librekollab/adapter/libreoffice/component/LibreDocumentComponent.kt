package org.msc.librekollab.adapter.libreoffice.component

import com.sun.star.container.XEnumerationAccess
import com.sun.star.frame.XDesktop
import com.sun.star.frame.XModel
import com.sun.star.frame.XStorable
import com.sun.star.lang.XMultiComponentFactory
import com.sun.star.text.XTextDocument
import com.sun.star.uno.UnoRuntime
import com.sun.star.uno.XComponentContext
import org.msc.librekollab.adapter.libreoffice.UnoClient

class LibreDocumentComponent(
    private val componentContext: XComponentContext,
    private val unoClient: UnoClient
) : DocumentComponent {

    companion object {
        private const val DESKTOP_SERVICE = "com.sun.star.frame.Desktop"
    }

    override suspend fun listDocuments(): List<String> =
        openDocuments().map { (_, url) -> url.substringAfterLast('/') }.toList()

    override suspend fun <T> withDocument(documentId: String, block: (XTextDocument) -> T): T =
        block(findDocument(documentId))

    override suspend fun <T> withDocumentMutating(documentId: String, block: (XTextDocument) -> T): T {
        val doc = findDocument(documentId)
        val result = block(doc)
        UnoRuntime.queryInterface(XStorable::class.java, doc)?.store()
        return result
    }

    private fun getDesktop(): XDesktop {
        val serviceManager = UnoRuntime.queryInterface(XMultiComponentFactory::class.java, componentContext.serviceManager)
        return UnoRuntime.queryInterface(
            XDesktop::class.java,
            serviceManager.createInstanceWithContext(DESKTOP_SERVICE, componentContext)
        )
    }

    private fun openDocuments(): Sequence<Pair<XTextDocument, String>> {
        val enumeration = UnoRuntime.queryInterface(XEnumerationAccess::class.java, getDesktop().components).createEnumeration()
        return unoClient.enumerationSequence(enumeration)
            .mapNotNull { UnoRuntime.queryInterface(XTextDocument::class.java, it) }
            .mapNotNull { doc -> UnoRuntime.queryInterface(XModel::class.java, doc)?.url?.let { doc to it } }
    }

    private fun findDocument(documentId: String): XTextDocument =
        openDocuments().firstOrNull { (_, url) -> url.endsWith("/$documentId") || url == documentId }?.first
            ?: throw NoSuchElementException("No open document found for: $documentId")
}
