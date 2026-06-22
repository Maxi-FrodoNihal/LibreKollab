package org.msc.liberekollab.abstrakt

import org.msc.liberekollab.model.Comment
import org.msc.liberekollab.model.TextAnchor

interface KollabAPI {
    suspend fun getText(documentId: String): String
    suspend fun getPageCount(documentId: String): Int
    suspend fun getChapters(documentId: String): List<String>
    suspend fun getTextByPages(documentId: String, fromPage: Int, toPage: Int): String
    suspend fun getTextByChapter(documentId: String, chapter: String): String
    suspend fun getComments(documentId: String): List<Comment>

    suspend fun setEditMode(documentId: String, editMode: Boolean)
    suspend fun getEditMode(documentId: String): Boolean

    suspend fun addComment(documentId: String, commentText: String, author: String, anchor: TextAnchor)
    // switch to assertj
    // getComment and deleteComment und updateComment aufbauen
}