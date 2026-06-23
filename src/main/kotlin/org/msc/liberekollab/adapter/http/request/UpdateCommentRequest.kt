package org.msc.liberekollab.adapter.http.request

import kotlinx.serialization.Serializable

@Serializable
data class UpdateCommentRequest(val newText: String)
