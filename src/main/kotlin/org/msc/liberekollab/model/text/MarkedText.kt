package org.msc.liberekollab.model.text

import kotlinx.serialization.Serializable
import org.msc.liberekollab.model.text.properties.TextProperty

@Serializable
data class MarkedText(val text: String, val properties: List<TextProperty> = listOf())
