package org.msc.librekollab.adapter.mcp.request

import kotlinx.serialization.Serializable

@Serializable
data class AddCommentRequest(
    val documentId: String,
    val commentText: String,
    val author: String,
    val anchorText: String,
    val anchorParagraphIndex: Int,
    val anchorCharStart: Int,
    val anchorCharEnd: Int
)
