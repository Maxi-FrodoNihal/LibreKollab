package org.msc.liberekollab.adapter.http.response

import kotlinx.serialization.Serializable
import org.msc.liberekollab.domain.model.Comment

@Serializable
data class CommentsResponse(val comments: List<Comment>)
