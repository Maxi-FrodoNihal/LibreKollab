package org.msc.liberekollab.domain.model.text

import kotlinx.serialization.Serializable
import org.msc.liberekollab.domain.model.text.properties.TextProperty

@Serializable
data class MarkedText(val text: String, val properties: List<TextProperty> = listOf())
