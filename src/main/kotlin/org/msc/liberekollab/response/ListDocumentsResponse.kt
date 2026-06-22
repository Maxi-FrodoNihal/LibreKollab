package org.msc.liberekollab.response

import kotlinx.serialization.Serializable

@Serializable
data class ListDocumentsResponse(val documents: List<String>)
