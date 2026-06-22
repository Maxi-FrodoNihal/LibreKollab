package org.msc.liberekollab.request

import kotlinx.serialization.Serializable
import org.msc.liberekollab.model.TextAnchor

@Serializable
data class AddCommentRequest(val commentText: String, val author: String, val anchor: TextAnchor)
