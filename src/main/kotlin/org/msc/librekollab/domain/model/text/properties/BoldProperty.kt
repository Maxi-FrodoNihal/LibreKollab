package org.msc.librekollab.domain.model.text.properties

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.msc.librekollab.domain.model.text.MarkIndex

@Serializable
@SerialName("bold")
data class BoldProperty(override val markIndex: MarkIndex) : TextProperty
