package org.msc.liberekollab.adapter.http.request

import kotlinx.serialization.Serializable
import org.msc.liberekollab.domain.model.TextAnchor
import org.msc.liberekollab.domain.model.text.MarkedText

@Serializable
data class EditTextRequest(val anchor: TextAnchor, val newText: MarkedText)
