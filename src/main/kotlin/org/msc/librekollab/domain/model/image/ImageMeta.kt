package org.msc.librekollab.domain.model.image

import kotlinx.serialization.Serializable
import org.msc.librekollab.domain.model.TextAnchor

@Serializable
data class ImageMeta(val imageId: String, val width: Int, val height: Int, val sizeMb: Double, val page: Int, val textAnchor: TextAnchor)
