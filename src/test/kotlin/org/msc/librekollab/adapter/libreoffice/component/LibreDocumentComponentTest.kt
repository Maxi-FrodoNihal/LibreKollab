package org.msc.librekollab.adapter.libreoffice.component

import com.sun.star.container.XEnumeration
import com.sun.star.container.XEnumerationAccess
import com.sun.star.frame.XDesktop
import com.sun.star.frame.XStorable
import com.sun.star.lang.XMultiComponentFactory
import com.sun.star.text.XTextDocument
import com.sun.star.uno.XComponentContext
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.msc.librekollab.adapter.libreoffice.UnoClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// LibreDocumentComponent talks only to XDesktop (already-open documents held in memory), never the filesystem,
// and UnoRuntime.queryInterface returns a mock as-is once it already implements the requested interface
// (a plain Class.isInstance check, no bridge involved) -- so a mocked XDesktop is enough, no live LibreOffice needed.
class LibreDocumentComponentTest {

    private val unoClient = UnoClient()

    @Test
    fun `T01 listDocuments returns file names of open documents`() = runBlocking {
        val component = componentWithOpenDocuments(
            "file:///workspace/report.odt" to mockk<XTextDocument>(),
            "file:///workspace/notes.odt" to mockk<XTextDocument>(),
        )

        assertEquals(listOf("report.odt", "notes.odt"), component.listDocuments())
    }

    @Test
    fun `T02 withDocument resolves a document by exact url match`() = runBlocking {
        val doc = mockk<XTextDocument>()
        val component = componentWithOpenDocuments("report.odt" to doc)

        assertEquals(doc, component.withDocument("report.odt") { it })
    }

    @Test
    fun `T03 withDocument resolves a document by url suffix match`() = runBlocking {
        val doc = mockk<XTextDocument>()
        val component = componentWithOpenDocuments("file:///workspace/report.odt" to doc)

        assertEquals(doc, component.withDocument("report.odt") { it })
    }

    @Test
    fun `T04 withDocument throws NoSuchElementException for an unknown documentId`(): Unit = runBlocking {
        val component = componentWithOpenDocuments("file:///workspace/report.odt" to mockk<XTextDocument>())

        assertFailsWith<NoSuchElementException> {
            component.withDocument("missing.odt") { it }
        }
    }

    @Test
    fun `T05 withDocumentMutating stores the document after the block runs and returns its result`() = runBlocking {
        val doc = mockk<XTextDocument>(moreInterfaces = arrayOf(XStorable::class))
        every { (doc as XStorable).store() } just Runs
        val component = componentWithOpenDocuments("report.odt" to doc)

        val result = component.withDocumentMutating("report.odt") { "edited" }

        assertEquals("edited", result)
        verify { (doc as XStorable).store() }
    }

    private fun componentWithOpenDocuments(vararg documents: Pair<String, XTextDocument>): LibreDocumentComponent {
        documents.forEach { (url, doc) -> every { doc.url } returns url }

        val remaining = documents.map { it.second }.toMutableList()
        val enumeration = mockk<XEnumeration>()
        every { enumeration.hasMoreElements() } answers { remaining.isNotEmpty() }
        every { enumeration.nextElement() } answers { remaining.removeAt(0) }

        val enumerationAccess = mockk<XEnumerationAccess>()
        every { enumerationAccess.createEnumeration() } returns enumeration

        val desktop = mockk<XDesktop>()
        every { desktop.components } returns enumerationAccess

        val serviceManager = mockk<XMultiComponentFactory>()
        every { serviceManager.createInstanceWithContext(any(), any()) } returns desktop

        val context = mockk<XComponentContext>()
        every { context.serviceManager } returns serviceManager

        return LibreDocumentComponent(context, unoClient)
    }
}
