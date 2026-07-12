package org.msc.librekollab.domain.model.anchor

import kotlinx.serialization.Serializable
import org.msc.librekollab.domain.model.id.HashIdGenerator

@Serializable
data class TextAnchor(
    val text: String,
    val paragraphIndex: Int,
    val charStart: Int,
    val charEnd: Int,
    val id: String = HashIdGenerator.generate("$text:$paragraphIndex:$charStart:$charEnd".toByteArray())
)
