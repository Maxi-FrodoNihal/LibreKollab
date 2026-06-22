package org.msc.liberekollab.model

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class Change(
    val action: ChangeAction,
    val author: String,
    val dateTime: LocalDateTime,
    val text: String,
    val anchor: TextAnchor
)
