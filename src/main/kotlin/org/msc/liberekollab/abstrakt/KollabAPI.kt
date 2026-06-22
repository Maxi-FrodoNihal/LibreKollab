package org.msc.liberekollab.abstrakt

import org.msc.liberekollab.model.Change
import org.msc.liberekollab.model.ChangeStatus
import org.msc.liberekollab.model.Comment
import org.msc.liberekollab.model.TextAnchor

interface KollabAPI {
    suspend fun getText(documentId: String, changeStatus: ChangeStatus = ChangeStatus.FUSION): String
    suspend fun editText(documentId: String, anchor: TextAnchor, newText: String)
    suspend fun getChanges(documentId: String): List<Change>
    suspend fun getPageCount(documentId: String): Int
    suspend fun getChapters(documentId: String): List<String>
    suspend fun getTextByPages(documentId: String, fromPage: Int, toPage: Int, changeStatus: ChangeStatus = ChangeStatus.FUSION): String
    suspend fun getTextByChapter(documentId: String, chapter: String, changeStatus: ChangeStatus = ChangeStatus.FUSION): String

    suspend fun setEditMode(documentId: String, editMode: Boolean)
    suspend fun getEditMode(documentId: String): Boolean

    suspend fun getComments(documentId: String): List<Comment>
    suspend fun getComment(documentId: String, commentId: String): Comment?
    suspend fun addComment(documentId: String, commentText: String, author: String, anchor: TextAnchor)
    suspend fun updateComment(documentId: String, commentId: String, newText: String)
    suspend fun deleteComment(documentId: String, commentId: String)
    // Logs
    // Kursiv
}
