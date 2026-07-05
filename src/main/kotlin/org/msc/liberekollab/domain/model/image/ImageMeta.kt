package org.msc.liberekollab.domain.model.image

import kotlinx.serialization.Serializable
import org.msc.liberekollab.domain.model.TextAnchor

@Serializable
data class ImageMeta(val imageId: String, val width: Int, val height: Int, val sizeMb: Double, val page: Int, val textAnchor: TextAnchor)
