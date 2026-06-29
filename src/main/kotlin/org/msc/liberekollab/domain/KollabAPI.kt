package org.msc.liberekollab.domain

import org.msc.liberekollab.domain.model.change.Change
import org.msc.liberekollab.domain.model.change.ChangeStatus
import org.msc.liberekollab.domain.model.Comment
import org.msc.liberekollab.domain.model.TextAnchor
import org.msc.liberekollab.domain.model.text.MarkedText

/**
 * Central port of LibereKollab. Exposes LibreOffice document editing as MCP tools over SSE.
 *
 * Build: ./gradlew oxt  →  build/oxt/LibereKollab-1.0-SNAPSHOT.oxt
 */
interface KollabAPI {
    suspend fun listDocuments(): List<String>

    suspend fun editText(documentId: String, anchor: TextAnchor, newText: MarkedText, author: String = "")

    suspend fun getChanges(documentId: String): List<Change>
    suspend fun getPageCount(documentId: String): Int
    suspend fun getChapters(documentId: String): List<String>

    suspend fun getText(documentId: String, changeStatus: ChangeStatus = ChangeStatus.FUSION): MarkedText
    suspend fun getTextByPages(documentId: String, fromPage: Int, toPage: Int, changeStatus: ChangeStatus = ChangeStatus.FUSION): MarkedText
    suspend fun getTextByChapter(documentId: String, chapter: String, changeStatus: ChangeStatus = ChangeStatus.FUSION): MarkedText

    suspend fun setEditMode(documentId: String, editMode: Boolean)
    suspend fun getEditMode(documentId: String): Boolean

    suspend fun getComments(documentId: String): List<Comment>
    suspend fun getComment(documentId: String, commentId: String): Comment?
    suspend fun addComment(documentId: String, commentText: String, author: String, anchor: TextAnchor)
    suspend fun updateComment(documentId: String, commentId: String, newText: String)
    suspend fun deleteComment(documentId: String, commentId: String)
}
