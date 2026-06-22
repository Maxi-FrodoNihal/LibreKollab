package org.msc.liberekollab.request

import kotlinx.serialization.Serializable

@Serializable
data class UpdateCommentRequest(val newText: String)
