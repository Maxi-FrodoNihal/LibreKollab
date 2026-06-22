package org.msc.liberekollab.request

import kotlinx.serialization.Serializable
import org.msc.liberekollab.model.TextAnchor

@Serializable
data class EditTextRequest(val anchor: TextAnchor, val newText: String)
