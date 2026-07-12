package org.msc.librekollab.domain.model.id

import java.security.MessageDigest

object HashIdGenerator : IdGenerator {
    private const val HASH_ALGORITHM = "SHA-256"
    private const val HEX_FORMAT = "%02x"
    private const val ID_LENGTH = 12

    override fun generate(bytes: ByteArray): String =
        MessageDigest.getInstance(HASH_ALGORITHM)
            .digest(bytes)
            .joinToString("") { HEX_FORMAT.format(it) }
            .take(ID_LENGTH)
}
