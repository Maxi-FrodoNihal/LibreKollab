package org.msc.liberekollab.model.change

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import org.msc.liberekollab.model.TextAnchor

@Serializable
data class Change(
    val action: ChangeAction,
    val author: String,
    val dateTime: LocalDateTime,
    val text: String,
    val anchor: TextAnchor
)
