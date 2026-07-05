package org.msc.librekollab.domain.model

import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
data class TextAnchor(
    val text: String,
    val paragraphIndex: Int,
    val charStart: Int,
    val charEnd: Int,
    val id: String = computeAnchorId(text, paragraphIndex, charStart, charEnd)
) {
    companion object {
        private const val HASH_ALGORITHM = "SHA-256"
        private const val HEX_FORMAT = "%02x"

        private fun computeAnchorId(text: String, paragraphIndex: Int, charStart: Int, charEnd: Int): String =
            MessageDigest.getInstance(HASH_ALGORITHM)
                .digest("$text:$paragraphIndex:$charStart:$charEnd".toByteArray())
                .joinToString("") { HEX_FORMAT.format(it) }
                .take(12)
    }
}


