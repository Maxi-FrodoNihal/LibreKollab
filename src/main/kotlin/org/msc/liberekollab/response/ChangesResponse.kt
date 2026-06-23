package org.msc.liberekollab.response

import kotlinx.serialization.Serializable
import org.msc.liberekollab.model.change.Change

@Serializable
data class ChangesResponse(val changes: List<Change>)
