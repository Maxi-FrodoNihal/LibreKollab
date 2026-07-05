package org.msc.librekollab.domain.model.text.properties

import kotlinx.serialization.Serializable
import org.msc.librekollab.domain.model.text.MarkIndex

@Serializable
sealed class TextProperty {
    abstract val markIndex: MarkIndex
}
