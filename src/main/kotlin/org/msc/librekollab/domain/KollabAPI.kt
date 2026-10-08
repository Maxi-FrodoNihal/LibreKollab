package org.msc.librekollab.domain

import org.msc.librekollab.domain.model.change.Change
import org.msc.librekollab.domain.model.change.ChangeStatus
import org.msc.librekollab.domain.model.Comment
import org.msc.librekollab.domain.model.SearchResult
import org.msc.librekollab.domain.model.anchor.TextAnchor
import org.msc.librekollab.domain.model.image.Image
import org.msc.librekollab.domain.model.image.ImageMeta
import org.msc.librekollab.domain.model.text.MarkedText

/**
 * Central port of LibreKollab. Exposes LibreOffice document editing as MCP tools over SSE and Streamable HTTP.
 *
 * Build: ./gradlew oxt  →  build/oxt/LibreKollab-1.1.0.oxt
 */
interface KollabAPI {
    companion object {
        const val UNKNOWN_AUTHOR = "Unknown Author"
    }

    suspend fun listDocuments(): List<String>

    suspend fun editText(documentId: String, anchor: TextAnchor, newText: MarkedText, author: String = UNKNOWN_AUTHOR)

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
    suspend fun addComment(documentId: String, commentText: String, author: String, anchor: TextAnchor): Comment
    suspend fun updateComment(documentId: String, commentId: String, newText: String)
    suspend fun deleteComment(documentId: String, commentId: String)

    suspend fun getImageMetas(documentId: String): List<ImageMeta>
    suspend fun getImage(documentId: String, imageId: String): Image?

    suspend fun search(documentId: String, searchText: String, page: Int = 1, size: Int = 10): SearchResult
}
