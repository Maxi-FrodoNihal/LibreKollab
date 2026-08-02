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

// Test-only DocumentComponent that loads each document once and keeps it open across every subsequent
// call, instead of reloading it fresh from disk per call like TestDocumentComponent does. This mirrors
// production's LibreDocumentComponent, which always operates on an already-open, long-lived document —
// needed to reproduce bugs that only manifest once UNO state (cursors, redlines) accumulates across many
// edits on the same in-memory document, which a per-call reload-and-dispose cycle resets every time.
class PersistentTestDocumentComponent(
    private val componentContext: XComponentContext,
    private val basePath: String,
    private val containerWorkspacePath: String
) : DocumentComponent, AutoCloseable {

    private val openDocuments = mutableMapOf<String, XTextDocument>()

    companion object {
        private const val DESKTOP_SERVICE = "com.sun.star.frame.Desktop"
        private const val HIDDEN_PROPERTY = "Hidden"
        private const val NEW_FRAME_TARGET = "_blank"
    }

    override suspend fun listDocuments(): List<String> =
        File(basePath).listFiles()?.map { it.name } ?: emptyList()

    override suspend fun <T> withDocument(documentId: String, block: (XTextDocument) -> T): T =
        block(documentFor(documentId))

    override suspend fun <T> withDocumentMutating(documentId: String, block: (XTextDocument) -> T): T {
        val textDoc = documentFor(documentId)
        val result = block(textDoc)
        UnoRuntime.queryInterface(XStorable::class.java, textDoc).store()
        return result
    }

    override fun close() {
        openDocuments.values.forEach { UnoRuntime.queryInterface(XComponent::class.java, it)?.dispose() }
        openDocuments.clear()
    }

    private fun documentFor(documentId: String): XTextDocument = openDocuments.getOrPut(documentId) {
        val serviceManager = UnoRuntime.queryInterface(XMultiComponentFactory::class.java, componentContext.serviceManager)
        val desktop = UnoRuntime.queryInterface(
            XComponentLoader::class.java,
            serviceManager.createInstanceWithContext(DESKTOP_SERVICE, componentContext)
        )
        val loadProps = arrayOf(PropertyValue().apply { Name = HIDDEN_PROPERTY; Value = true })
        val component = desktop.loadComponentFromURL("file://$containerWorkspacePath/$documentId", NEW_FRAME_TARGET, 0, loadProps)
        UnoRuntime.queryInterface(XTextDocument::class.java, component)
    }
}
