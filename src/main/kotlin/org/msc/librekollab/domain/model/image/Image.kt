package org.msc.librekollab.domain.model.image

import org.msc.librekollab.domain.model.anchor.TextAnchor
import java.security.MessageDigest

data class Image(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val textAnchor: TextAnchor,
    val id: String = computeId(bytes, width, height, textAnchor),
) {
    companion object {
        private const val HASH_ALGORITHM = "SHA-256"
        private const val HEX_FORMAT = "%02x"

        private fun computeId(bytes: ByteArray, width: Int, height: Int, textAnchor: TextAnchor): String {
            val digest = MessageDigest.getInstance(HASH_ALGORITHM)
            digest.update(bytes)
            digest.update(":$width:$height:${textAnchor.id}".toByteArray())
            return digest.digest()
                .joinToString("") { HEX_FORMAT.format(it) }
                .take(12)
        }
    }

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
