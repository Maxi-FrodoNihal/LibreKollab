package org.msc.liberekollab.adapter.http.response

import kotlinx.serialization.Serializable

@Serializable
data class EditModeResponse(val active: Boolean)
