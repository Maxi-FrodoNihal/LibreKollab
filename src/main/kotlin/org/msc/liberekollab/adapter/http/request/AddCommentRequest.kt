package org.msc.liberekollab.adapter.http.request

import kotlinx.serialization.Serializable
import org.msc.liberekollab.domain.model.TextAnchor

@Serializable
data class AddCommentRequest(val commentText: String, val author: String, val anchor: TextAnchor)
