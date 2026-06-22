package org.msc.liberekollab.response

import kotlinx.serialization.Serializable
import org.msc.liberekollab.model.Comment

@Serializable
data class CommentsResponse(val comments: List<Comment>)
