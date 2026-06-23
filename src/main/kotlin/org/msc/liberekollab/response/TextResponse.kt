package org.msc.liberekollab.response

import kotlinx.serialization.Serializable
import org.msc.liberekollab.model.text.MarkedText

@Serializable
data class TextResponse(val text: MarkedText)
