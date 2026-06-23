package org.msc.liberekollab.domain.port

import java.io.InputStream
import java.io.OutputStream

interface IOAPI {
    fun nextId(): String
    suspend fun ls(): List<String>
    suspend fun upload(documentId: String, fileName: String, content: InputStream): String
    suspend fun download(documentId: String): OutputStream
    suspend fun delete(documentId: String)
}
