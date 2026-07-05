package org.msc.librekollab.adapter.libreoffice

import com.sun.star.beans.PropertyValue
import com.sun.star.beans.XPropertySet
import com.sun.star.container.XEnumerationAccess
import com.sun.star.frame.XDesktop
import com.sun.star.frame.XModel
import com.sun.star.frame.XStorable
import com.sun.star.lang.XMultiComponentFactory
import com.sun.star.lang.XMultiServiceFactory
import com.sun.star.text.XTextDocument
import com.sun.star.uno.UnoRuntime
import com.sun.star.uno.XComponentContext
import com.sun.star.util.XChangesBatch
import kotlinx.coroutines.withContext

class LibreKollab(context: XComponentContext) : CoreKollab(context) {

    private fun getDesktop(): XDesktop {
        val serviceManager = UnoRuntime.queryInterface(XMultiComponentFactory::class.java, componentContext.serviceManager)
        return UnoRuntime.queryInterface(
            XDesktop::class.java,
            serviceManager.createInstanceWithContext("com.sun.star.frame.Desktop", componentContext)
        )
    }

    override suspend fun listDocuments(): List<String> = withContext(libreOfficeDispatcher) {
        val components = UnoRuntime.queryInterface(XEnumerationAccess::class.java, getDesktop().components)
            .createEnumeration()
        val result = mutableListOf<String>()
        while (components.hasMoreElements()) {
            val doc = UnoRuntime.queryInterface(XTextDocument::class.java, components.nextElement()) ?: continue
            val url = UnoRuntime.queryInterface(XModel::class.java, doc)?.getURL() ?: continue
            result.add(url.substringAfterLast('/'))
        }
        result
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

    override suspend fun <T> withDocumentMutating(documentId: String, block: (XTextDocument) -> T): T =
        withContext(libreOfficeDispatcher) {
            val doc = findDocument(documentId)
            val result = block(doc)
            UnoRuntime.queryInterface(XStorable::class.java, doc)?.store()
            result
        }
}
