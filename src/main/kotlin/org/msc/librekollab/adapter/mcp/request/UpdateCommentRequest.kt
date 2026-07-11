package org.msc.librekollab.adapter.mcp.request

import kotlinx.serialization.Serializable

@Serializable
data class UpdateCommentRequest(val documentId: String, val commentId: String, val newText: String)
