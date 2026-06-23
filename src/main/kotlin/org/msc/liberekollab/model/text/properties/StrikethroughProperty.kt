package org.msc.liberekollab.model.text.properties

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.msc.liberekollab.model.text.MarkIndex

@Serializable
@SerialName("strikethrough")
data class StrikethroughProperty(override val markIndex: MarkIndex) : TextProperty()
