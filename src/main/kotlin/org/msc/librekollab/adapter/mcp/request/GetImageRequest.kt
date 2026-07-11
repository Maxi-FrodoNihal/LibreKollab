package org.msc.librekollab.adapter.mcp.request

import kotlinx.serialization.Serializable

@Serializable
data class GetImageRequest(val documentId: String, val imageId: String, val scale: Double? = null)
