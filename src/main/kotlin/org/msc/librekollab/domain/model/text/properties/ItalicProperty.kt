package org.msc.librekollab.domain.model.text.properties

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.msc.librekollab.domain.model.text.MarkIndex

@Serializable
@SerialName("italic")
data class ItalicProperty(override val markIndex: MarkIndex) : TextProperty
