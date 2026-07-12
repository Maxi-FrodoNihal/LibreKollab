package org.msc.librekollab.adapter.libreoffice

import com.sun.star.beans.XPropertySet
import com.sun.star.container.XEnumerationAccess
import com.sun.star.text.XPageCursor
import com.sun.star.text.XTextDocument
import com.sun.star.uno.UnoRuntime
import com.sun.star.uno.XComponentContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.msc.librekollab.adapter.libreoffice.component.AuthorComponent
import org.msc.librekollab.adapter.libreoffice.component.CommentComponent
import org.msc.librekollab.adapter.libreoffice.component.DocumentComponent
import org.msc.librekollab.adapter.libreoffice.component.ImageComponent
import org.msc.librekollab.adapter.libreoffice.component.LibreDocumentComponent
import org.msc.librekollab.adapter.libreoffice.component.RedlineComponent
import org.msc.librekollab.adapter.libreoffice.component.SearchComponent
import org.msc.librekollab.adapter.libreoffice.component.TextComponent
import org.msc.librekollab.domain.KollabAPI
import org.msc.librekollab.domain.model.Comment
import org.msc.librekollab.domain.model.SearchResult
import org.msc.librekollab.domain.model.anchor.TextAnchor
import org.msc.librekollab.domain.model.change.Change
import org.msc.librekollab.domain.model.change.ChangeStatus
import org.msc.librekollab.domain.model.image.Image
import org.msc.librekollab.domain.model.image.ImageMeta
import org.msc.librekollab.domain.model.text.MarkedText
import org.slf4j.LoggerFactory

open class LibreKollab(
    private val componentContext: XComponentContext,
    private val unoClient: UnoClient = UnoClient(),
    private val commentComponent: CommentComponent = CommentComponent(unoClient),
    private val searchComponent: SearchComponent = SearchComponent(unoClient),
    private val textComponent: TextComponent = TextComponent(unoClient),
    private val redlineComponent: RedlineComponent = RedlineComponent(unoClient),
    private val imageComponent: ImageComponent = ImageComponent(componentContext, unoClient),
    private val documentComponent: DocumentComponent = LibreDocumentComponent(componentContext, unoClient),
    private val authorComponent: AuthorComponent = AuthorComponent(componentContext)
) : KollabAPI {

    @Suppress("unused")
    private val log = LoggerFactory.getLogger(LibreKollab::class.java)
    private val libreOfficeDispatcher = Dispatchers.IO.limitedParallelism(1)

    companion object {
        private const val RECORD_CHANGES_PROPERTY = "RecordChanges"
    }

    override suspend fun listDocuments(): List<String> = withContext(libreOfficeDispatcher) {
        documentComponent.listDocuments()
    }

    override suspend fun getText(documentId: String, changeStatus: ChangeStatus): MarkedText =
        withContext(libreOfficeDispatcher) {
            withDocument(documentId) { textDoc -> textComponent.getText(textDoc, changeStatus) }
        }

    override suspend fun getChanges(documentId: String): List<Change> =
        withContext(libreOfficeDispatcher) {
            withDocument(documentId) { textDoc ->
                val paragraphs = UnoRuntime.queryInterface(XEnumerationAccess::class.java, textDoc.text)
                    .createEnumeration()
                unoClient.enumerationSequence(paragraphs)
                    .flatMapIndexed { paragraphIdx, para -> redlineComponent.changesInParagraph(para, paragraphIdx) }
                    .toList()
            }
        }

    override suspend fun editText(documentId: String, anchor: TextAnchor, newText: MarkedText, author: String) {
        withEnsuredEditMode(documentId) {
            withContext(libreOfficeDispatcher) {
                withDocumentMutating(documentId) { textDoc ->
                    authorComponent.withAuthor(author) {
                        textComponent.editText(textDoc, anchor, newText)
                    }
                }
            }
        }
    }

    override suspend fun getPageCount(documentId: String): Int = withContext(libreOfficeDispatcher) {
        withDocument(documentId) { textDoc ->
            val vc = unoClient.viewCursorOf(textDoc)
            vc.gotoEnd(false)
            UnoRuntime.queryInterface(XPageCursor::class.java, vc).page.toInt()
        }
    }

    override suspend fun getChapters(documentId: String): List<String> = withContext(libreOfficeDispatcher) {
        withDocument(documentId) { textDoc -> textComponent.getChapters(textDoc) }
    }

    override suspend fun getTextByPages(documentId: String, fromPage: Int, toPage: Int, changeStatus: ChangeStatus): MarkedText =
        withContext(libreOfficeDispatcher) {
            withDocument(documentId) { textDoc -> textComponent.getTextByPages(textDoc, fromPage, toPage, changeStatus) }
        }

    override suspend fun getTextByChapter(documentId: String, chapter: String, changeStatus: ChangeStatus): MarkedText =
        withContext(libreOfficeDispatcher) {
            withDocument(documentId) { textDoc -> textComponent.getTextByChapter(textDoc, chapter, changeStatus) }
        }

    override suspend fun setEditMode(documentId: String, editMode: Boolean): Unit = withContext(libreOfficeDispatcher) {
        withDocumentMutating(documentId) { textDoc ->
            UnoRuntime.queryInterface(XPropertySet::class.java, textDoc)
                .setPropertyValue(RECORD_CHANGES_PROPERTY, editMode)
        }
    }

    override suspend fun getEditMode(documentId: String): Boolean = withContext(libreOfficeDispatcher) {
        withDocument(documentId) { textDoc ->
            UnoRuntime.queryInterface(XPropertySet::class.java, textDoc)
                .getPropertyValue(RECORD_CHANGES_PROPERTY) as Boolean
        }
    }

    override suspend fun getComments(documentId: String): List<Comment> = withContext(libreOfficeDispatcher) {
        withDocument(documentId) { textDoc -> commentComponent.getComments(textDoc) }
    }

    override suspend fun getComment(documentId: String, commentId: String): Comment? =
        getComments(documentId).find { it.id == commentId }

    override suspend fun updateComment(documentId: String, commentId: String, newText: String): Unit =
        withContext(libreOfficeDispatcher) {
            withDocumentMutating(documentId) { textDoc ->
                commentComponent.updateComment(textDoc, documentId, commentId, newText)
            }
        }

    override suspend fun deleteComment(documentId: String, commentId: String): Unit =
        withContext(libreOfficeDispatcher) {
            withDocumentMutating(documentId) { textDoc ->
                commentComponent.deleteComment(textDoc, documentId, commentId)
            }
        }

    override suspend fun addComment(documentId: String, commentText: String, author: String, anchor: TextAnchor) {
        withContext(libreOfficeDispatcher) {
            withDocumentMutating(documentId) { textDoc ->
                commentComponent.addComment(textDoc, commentText, author, anchor)
            }
        }
    }

    override suspend fun getImageMetas(documentId: String): List<ImageMeta> = withContext(libreOfficeDispatcher) {
        withDocument(documentId) { textDoc -> imageComponent.getImageMetas(documentId, textDoc) }
    }

    override suspend fun getImage(documentId: String, imageId: String): Image? {
        imageComponent.getCachedImage(imageId)?.let { return it }
        return withContext(libreOfficeDispatcher) {
            withDocument(documentId) { textDoc -> imageComponent.getImage(documentId, textDoc, imageId) }
        }
    }

    override suspend fun search(documentId: String, searchText: String, page: Int, size: Int): SearchResult = withContext(libreOfficeDispatcher) {
        withDocument(documentId) { textDoc ->
            searchComponent.search(textDoc, searchText, page, size) { para, idx ->
                textComponent.extractParagraphMarkedText(para, ChangeStatus.FUSION, idx).first
            }
        }
    }

    private suspend fun withEnsuredEditMode(documentId: String, block: suspend () -> Unit) {
        if (!getEditMode(documentId)){
            setEditMode(documentId, true)
        }
        block()
    }

    private suspend fun <T> withDocument(documentId: String, block: (XTextDocument) -> T): T =
        documentComponent.withDocument(documentId, block)

    private suspend fun <T> withDocumentMutating(documentId: String, block: (XTextDocument) -> T): T =
        documentComponent.withDocumentMutating(documentId, block)
}
