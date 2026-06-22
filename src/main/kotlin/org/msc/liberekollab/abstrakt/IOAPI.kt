package org.msc.liberekollab.abstrakt

import java.io.InputStream
import java.io.OutputStream

interface IOAPI {
    fun nextId(): String
    fun upload(documentId: String, fileName: String, content: InputStream): String
    fun download(documentId: String): OutputStream
    fun delete(documentId: String)
    fun ls(): List<String>
}
