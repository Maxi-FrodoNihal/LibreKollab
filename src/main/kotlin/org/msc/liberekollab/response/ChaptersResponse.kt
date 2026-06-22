package org.msc.liberekollab.response

import kotlinx.serialization.Serializable

@Serializable
data class ChaptersResponse(val chapters: List<String>)
