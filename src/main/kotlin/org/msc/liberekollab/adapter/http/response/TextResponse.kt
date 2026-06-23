package org.msc.liberekollab.adapter.http.response

import kotlinx.serialization.Serializable
import org.msc.liberekollab.domain.model.text.MarkedText

@Serializable
data class TextResponse(val text: MarkedText)
