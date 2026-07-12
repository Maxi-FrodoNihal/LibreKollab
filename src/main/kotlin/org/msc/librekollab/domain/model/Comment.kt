package org.msc.librekollab.domain.model

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import org.msc.librekollab.domain.model.anchor.TextAnchor
import org.msc.librekollab.domain.model.id.HashIdGenerator

@Serializable
data class Comment(
    val anchor: TextAnchor,
    val author: String,
    val content: String,
    val dateTime: LocalDateTime,
    // content/dateTime are excluded: updateComment changes both, and the id must survive an update.
    // Same author commenting the exact same anchor twice still collides — accepted as a rare edge case.
    val id: String = HashIdGenerator.generate("${anchor.id}:$author".toByteArray())
)
