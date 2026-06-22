package org.msc.liberekollab.response

import kotlinx.serialization.Serializable
import org.msc.liberekollab.model.Change

@Serializable
data class ChangesResponse(val changes: List<Change>)
