package org.msc.liberekollab.model

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class Comment(
    val id: String,
    val anchor: TextAnchor,
    val author: String,
    val content: String,
    val dateTime: LocalDateTime
) {
    companion object {
        fun of(anchor: TextAnchor, author: String, content: String, dateTime: LocalDateTime): Comment =
            Comment(anchor.toHash(), anchor, author, content, dateTime)
    }
}
