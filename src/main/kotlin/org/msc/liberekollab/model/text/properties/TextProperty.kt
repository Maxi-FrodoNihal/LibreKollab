package org.msc.liberekollab.model.text.properties

import kotlinx.serialization.Serializable
import org.msc.liberekollab.model.text.MarkIndex

@Serializable
sealed class TextProperty {
    abstract val markIndex: MarkIndex
}
