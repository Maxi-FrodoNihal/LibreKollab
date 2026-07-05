package org.msc.librekollab.domain.model.text.properties

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.msc.librekollab.domain.model.text.MarkIndex

@Serializable
@SerialName("underline")
data class UnderlineProperty(override val markIndex: MarkIndex) : TextProperty()
