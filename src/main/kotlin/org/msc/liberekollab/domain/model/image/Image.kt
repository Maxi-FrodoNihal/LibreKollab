package org.msc.liberekollab.domain.model.image

import kotlinx.serialization.Serializable
import org.msc.liberekollab.domain.model.TextAnchor
import java.security.MessageDigest
import java.util.Base64

@Serializable
data class Image(
    val data: String,
    val width: Int,
    val height: Int,
    val textAnchor: TextAnchor,
    val id: String = computeId(data, width, height, textAnchor),
) {
    companion object {
        private fun computeId(data: String, width: Int, height: Int, textAnchor: TextAnchor): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(Base64.getDecoder().decode(data))
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
