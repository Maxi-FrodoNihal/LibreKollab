package org.msc.librekollab.domain.model.change

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import org.msc.librekollab.domain.model.anchor.TextAnchor

@Serializable
data class Change(
    val action: ChangeAction,
    val author: String,
    val dateTime: LocalDateTime,
    val text: String,
    val anchor: TextAnchor
)
