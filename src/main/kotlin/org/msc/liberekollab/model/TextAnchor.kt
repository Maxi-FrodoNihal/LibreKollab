package org.msc.liberekollab.model

import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
data class TextAnchor(
    val text: String,
    val paragraphIndex: Int,
    val charStart: Int,
    val charEnd: Int
)

fun TextAnchor.toHash(): String =
    MessageDigest.getInstance("SHA-256")
        .digest("$text:$paragraphIndex:$charStart:$charEnd".toByteArray())
        .joinToString("") { "%02x".format(it) }
