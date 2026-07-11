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
import org.msc.librekollab.domain.KollabAPI

class LibreKollab(context: XComponentContext) : CoreKollab(context) {

    companion object {
        private const val DESKTOP_SERVICE = "com.sun.star.frame.Desktop"
        private const val CONFIG_PROVIDER_SERVICE = "com.sun.star.configuration.ConfigurationProvider"
        private const val CONFIG_UPDATE_ACCESS_SERVICE = "com.sun.star.configuration.ConfigurationUpdateAccess"
        private const val NODEPATH_PROPERTY_NAME = "nodepath"
        private const val USER_PROFILE_NODE_PATH = "/org.openoffice.UserProfile/Data"
        private const val GIVEN_NAME_PROPERTY = "givenname"
        private const val SURNAME_PROPERTY = "sn"
    }

    override suspend fun listDocuments(): List<String> = withContext(libreOfficeDispatcher) {
        openDocuments().map { (_, url) -> url.substringAfterLast('/') }.toList()
    }

    override suspend fun <T> withDocument(documentId: String, block: (XTextDocument) -> T): T =
        withContext(libreOfficeDispatcher) { block(findDocument(documentId)) }

    override fun withAuthor(author: String, block: () -> Unit) {
        if (author == KollabAPI.UNKNOWN_AUTHOR) {
            block()
            return
        }
        val access = userProfileAccess()
        val oldFirst = access.getPropertyValue(GIVEN_NAME_PROPERTY) as String
        val oldLast = access.getPropertyValue(SURNAME_PROPERTY) as String
        try {
            setProfileName(access, author, "")
            block()
        } finally {
            setProfileName(access, oldFirst, oldLast)
        }
    }

    override suspend fun <T> withDocumentMutating(documentId: String, block: (XTextDocument) -> T): T {
        return withContext(libreOfficeDispatcher) {
            val doc = findDocument(documentId)
            val result = block(doc)
            UnoRuntime.queryInterface(XStorable::class.java, doc)?.store()
            result
        }
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
        return enumerationSequence(enumeration)
            .mapNotNull { UnoRuntime.queryInterface(XTextDocument::class.java, it) }
            .mapNotNull { doc -> UnoRuntime.queryInterface(XModel::class.java, doc)?.url?.let { doc to it } }
    }

    private fun findDocument(documentId: String): XTextDocument =
        openDocuments().firstOrNull { (_, url) -> url.endsWith("/$documentId") || url == documentId }?.first
            ?: throw NoSuchElementException("No open document found for: $documentId")

    private fun userProfileAccess(): XPropertySet {
        val configProvider = UnoRuntime.queryInterface(
            XMultiServiceFactory::class.java,
            componentContext.serviceManager.createInstanceWithContext(CONFIG_PROVIDER_SERVICE, componentContext)
        )
        val nodeArg = PropertyValue().apply { Name = NODEPATH_PROPERTY_NAME; Value = USER_PROFILE_NODE_PATH }
        return UnoRuntime.queryInterface(
            XPropertySet::class.java,
            configProvider.createInstanceWithArguments(CONFIG_UPDATE_ACCESS_SERVICE, arrayOf(nodeArg))
        )
    }

    private fun setProfileName(access: XPropertySet, givenName: String, surname: String) {
        access.setPropertyValue(GIVEN_NAME_PROPERTY, givenName)
        access.setPropertyValue(SURNAME_PROPERTY, surname)
        UnoRuntime.queryInterface(XChangesBatch::class.java, access).commitChanges()
    }
}
