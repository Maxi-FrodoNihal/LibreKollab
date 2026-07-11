package org.msc.librekollab.domain.model.anchor

import kotlinx.serialization.Serializable

@Serializable
data class PageAnchor(val textAnchor: TextAnchor, val page: Int)
