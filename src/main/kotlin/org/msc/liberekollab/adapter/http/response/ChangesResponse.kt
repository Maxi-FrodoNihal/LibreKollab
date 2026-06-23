package org.msc.liberekollab.adapter.http.response

import kotlinx.serialization.Serializable
import org.msc.liberekollab.domain.model.change.Change

@Serializable
data class ChangesResponse(val changes: List<Change>)
