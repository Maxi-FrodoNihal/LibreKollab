package org.msc.librekollab.domain.model

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class Comment(
    val anchor: TextAnchor,
    val author: String,
    val content: String,
    val dateTime: LocalDateTime,
    val id: String = anchor.id
)
