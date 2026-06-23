package org.msc.liberekollab.request

import kotlinx.serialization.Serializable
import org.msc.liberekollab.model.TextAnchor
import org.msc.liberekollab.model.text.MarkedText

@Serializable
data class EditTextRequest(val anchor: TextAnchor, val newText: MarkedText)
