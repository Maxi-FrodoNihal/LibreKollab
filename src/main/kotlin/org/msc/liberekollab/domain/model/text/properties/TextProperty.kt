package org.msc.liberekollab.domain.model.text.properties

import kotlinx.serialization.Serializable
import org.msc.liberekollab.domain.model.text.MarkIndex

@Serializable
sealed class TextProperty {
    abstract val markIndex: MarkIndex
}
