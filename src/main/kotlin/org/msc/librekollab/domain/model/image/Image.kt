package org.msc.librekollab.domain.model.image

import org.msc.librekollab.domain.model.TextAnchor
import java.security.MessageDigest

data class Image(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val textAnchor: TextAnchor,
    val id: String = computeId(bytes, width, height, textAnchor),
) {
    companion object {
        private fun computeId(bytes: ByteArray, width: Int, height: Int, textAnchor: TextAnchor): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(bytes)
            digest.update(":$width:$height:${textAnchor.id}".toByteArray())
            return digest.digest()
                .joinToString("") { "%02x".format(it) }
                .take(12)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Image
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
