package org.msc.librekollab.domain.model.text

import kotlinx.serialization.Serializable
import org.msc.librekollab.domain.model.text.properties.TextProperty

@Serializable
data class MarkedText(val text: String, val properties: List<TextProperty> = listOf())
