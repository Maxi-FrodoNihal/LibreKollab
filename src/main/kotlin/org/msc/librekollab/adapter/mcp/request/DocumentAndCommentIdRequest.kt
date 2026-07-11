package org.msc.librekollab.adapter.mcp.request

import kotlinx.serialization.Serializable

@Serializable
data class DocumentAndCommentIdRequest(val documentId: String, val commentId: String)
