package org.msc.librekollab.domain.model.image

import org.msc.librekollab.domain.model.id.HashIdGenerator
import org.msc.librekollab.domain.model.anchor.TextAnchor

data class Image(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val textAnchor: TextAnchor,
    val id: String = HashIdGenerator.generate(bytes + ":$width:$height:${textAnchor.id}".toByteArray()),
) {
    // data class equals/hashCode would compare `bytes` by reference (ByteArray doesn't override equals);
    // id is already a content hash of bytes+width+height+anchor, so delegate to it instead.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Image
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
