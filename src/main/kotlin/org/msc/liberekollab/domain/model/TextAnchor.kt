package org.msc.liberekollab.domain.model

import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
data class TextAnchor(
    val text: String,
    val paragraphIndex: Int,
    val charStart: Int,
    val charEnd: Int,
    val id: String = computeAnchorId(text, paragraphIndex, charStart, charEnd)
){
    companion object {
        private fun computeAnchorId(text: String, paragraphIndex: Int, charStart: Int, charEnd: Int): String =
            MessageDigest.getInstance("SHA-256")
                .digest("$text:$paragraphIndex:$charStart:$charEnd".toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(12)
    }
}


