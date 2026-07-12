package org.msc.librekollab.domain.model.id

interface IdGenerator {
    fun generate(bytes: ByteArray): String
}
