package org.msc.librekollab.adapter.logging

import org.msc.librekollab.domain.KollabAPI
import org.msc.librekollab.domain.model.change.Change
import org.msc.librekollab.domain.model.change.ChangeStatus
import org.msc.librekollab.domain.model.Comment
import org.msc.librekollab.domain.model.anchor.TextAnchor
import org.msc.librekollab.domain.model.SearchResult
import org.msc.librekollab.domain.model.image.Image
import org.msc.librekollab.domain.model.image.ImageMeta
import org.msc.librekollab.domain.model.text.MarkedText
import org.slf4j.LoggerFactory

class LoggingKollabAPI(private val delegate: KollabAPI) : KollabAPI {

    private val log = LoggerFactory.getLogger(LoggingKollabAPI::class.java)

    companion object {
        private const val PREVIEW_LENGTH = 20
    }

    override suspend fun listDocuments(): List<String> {
        val result = delegate.listDocuments()
        log.info("listDocuments() = $result")
        return result
    }

    override suspend fun getText(documentId: String, changeStatus: ChangeStatus): MarkedText {
        val result = delegate.getText(documentId, changeStatus)
        log.info("getText(documentId=$documentId, changeStatus=$changeStatus) = \"${preview(result.text)}\"")
        return result
    }

    override suspend fun editText(documentId: String, anchor: TextAnchor, newText: MarkedText, author: String) {
        log.info("editText(documentId=$documentId, anchor=$anchor, author=$author, newText=\"${preview(newText.text)}\")")
        delegate.editText(documentId, anchor, newText, author)
    }

    override suspend fun getChanges(documentId: String): List<Change> {
        val result = delegate.getChanges(documentId)
        log.info("getChanges(documentId=$documentId) = ${result.size} changes")
        return result
    }

    override suspend fun getPageCount(documentId: String): Int {
        val result = delegate.getPageCount(documentId)
        log.info("getPageCount(documentId=$documentId) = $result")
        return result
    }

    override suspend fun getChapters(documentId: String): List<String> {
        val result = delegate.getChapters(documentId)
        log.info("getChapters(documentId=$documentId) = ${result.size} chapters")
        return result
    }

    override suspend fun getTextByPages(documentId: String, fromPage: Int, toPage: Int, changeStatus: ChangeStatus): MarkedText {
        val result = delegate.getTextByPages(documentId, fromPage, toPage, changeStatus)
        log.info("getTextByPages(documentId=$documentId, fromPage=$fromPage, toPage=$toPage, changeStatus=$changeStatus) = \"${preview(result.text)}\"")
        return result
    }

    override suspend fun getTextByChapter(documentId: String, chapter: String, changeStatus: ChangeStatus): MarkedText {
        val result = delegate.getTextByChapter(documentId, chapter, changeStatus)
        log.info("getTextByChapter(documentId=$documentId, chapter=$chapter, changeStatus=$changeStatus) = \"${preview(result.text)}\"")
        return result
    }

    override suspend fun setEditMode(documentId: String, editMode: Boolean) {
        log.info("setEditMode(documentId=$documentId, editMode=$editMode)")
        delegate.setEditMode(documentId, editMode)
    }

    override suspend fun getEditMode(documentId: String): Boolean {
        val result = delegate.getEditMode(documentId)
        log.info("getEditMode(documentId=$documentId) = $result")
        return result
    }

    override suspend fun getComments(documentId: String): List<Comment> {
        val result = delegate.getComments(documentId)
        log.info("getComments(documentId=$documentId) = ${result.size} comments")
        return result
    }

    override suspend fun getComment(documentId: String, commentId: String): Comment? {
        val result = delegate.getComment(documentId, commentId)
        log.info("getComment(documentId=$documentId, commentId=$commentId) = $result")
        return result
    }

    override suspend fun addComment(documentId: String, commentText: String, author: String, anchor: TextAnchor): Comment {
        val result = delegate.addComment(documentId, commentText, author, anchor)
        log.info("addComment(documentId=$documentId, author=$author, anchor=$anchor) = $result")
        return result
    }

    override suspend fun updateComment(documentId: String, commentId: String, newText: String) {
        log.info("updateComment(documentId=$documentId, commentId=$commentId)")
        delegate.updateComment(documentId, commentId, newText)
    }

    override suspend fun deleteComment(documentId: String, commentId: String) {
        log.info("deleteComment(documentId=$documentId, commentId=$commentId)")
        delegate.deleteComment(documentId, commentId)
    }

    override suspend fun getImageMetas(documentId: String): List<ImageMeta> {
        val result = delegate.getImageMetas(documentId)
        log.info("getImageMetas(documentId=$documentId) = ${result.size} images")
        return result
    }

    override suspend fun getImage(documentId: String, imageId: String): Image? {
        val result = delegate.getImage(documentId, imageId)
        val foundStatus = if (result != null) {
            "found"
        } else {
            "not found"
        }
        log.info("getImage(documentId=$documentId, imageId=$imageId) = $foundStatus")
        return result
    }

    override suspend fun search(documentId: String, searchText: String, page: Int, size: Int): SearchResult {
        val result = delegate.search(documentId, searchText, page, size)
        log.info("search(documentId=$documentId, searchText=$searchText, page=$page, size=$size) = ${result.totalFindings} findings")
        return result
    }

    private fun preview(text: String): String {
        if (text.length <= PREVIEW_LENGTH) {
            return text
        }
        return "${text.take(PREVIEW_LENGTH)}..."
    }
}
