package org.msc.librekollab.domain.model

import kotlinx.serialization.Serializable
import org.msc.librekollab.domain.model.anchor.PageAnchor

@Serializable
data class SearchResult(
    val page: Int,
    val size: Int,
    val totalFindings: Int,
    val elements: List<PageAnchor>
)
