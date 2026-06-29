package org.msc.liberekollab.adapter.logging

import org.msc.liberekollab.domain.KollabAPI
import org.msc.liberekollab.domain.model.change.Change
import org.msc.liberekollab.domain.model.change.ChangeStatus
import org.msc.liberekollab.domain.model.Comment
import org.msc.liberekollab.domain.model.TextAnchor
import org.msc.liberekollab.domain.model.text.MarkedText
import org.slf4j.LoggerFactory

class LoggingKollabAPI(private val delegate: KollabAPI) : KollabAPI {

    private val previewLength: Int = 20

    private val log = LoggerFactory.getLogger(LoggingKollabAPI::class.java)

    private fun preview(text: String): String =
        if (text.length <= previewLength) text else "${text.take(previewLength)}..."

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
        log.info("getChapters(documentId=$documentId)")
        return delegate.getChapters(documentId)
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

    override suspend fun addComment(documentId: String, commentText: String, author: String, anchor: TextAnchor) {
        log.info("addComment(documentId=$documentId, author=$author, anchor=$anchor)")
        delegate.addComment(documentId, commentText, author, anchor)
    }

    override suspend fun updateComment(documentId: String, commentId: String, newText: String) {
        log.info("updateComment(documentId=$documentId, commentId=$commentId)")
        delegate.updateComment(documentId, commentId, newText)
    }

    override suspend fun deleteComment(documentId: String, commentId: String) {
        log.info("deleteComment(documentId=$documentId, commentId=$commentId)")
        delegate.deleteComment(documentId, commentId)
    }
}
