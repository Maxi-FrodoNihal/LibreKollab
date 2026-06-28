package org.msc.liberekollab.adapter.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.msc.liberekollab.domain.port.IOAPI
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class LocalFileAdapter(private val basePath: String) : IOAPI {

    override fun nextId(): String = UUID.randomUUID().toString()

    override suspend fun ls(): List<String> = withContext(Dispatchers.IO) {
        File(basePath).listFiles()?.map { it.name } ?: emptyList()
    }

    override suspend fun upload(documentId: String, fileName: String, content: InputStream): String =
        withContext(Dispatchers.IO) {
            val file = File("$basePath/$fileName")
            file.parentFile?.mkdirs()
            file.outputStream().use { content.copyTo(it) }
            fileName
        }

    override suspend fun download(documentId: String): OutputStream =
        withContext(Dispatchers.IO) {
            val out = ByteArrayOutputStream()
            File("$basePath/$documentId").inputStream().use { it.copyTo(out) }
            out
        }

    override suspend fun delete(documentId: String): Unit = withContext(Dispatchers.IO) {
        File("$basePath/$documentId").delete()
    }

    override suspend fun <T> withReadableFile(documentId: String, block: suspend (File) -> T): T =
        block(File("$basePath/$documentId"))

    override suspend fun <T> withWritableFile(documentId: String, block: suspend (File) -> T): T =
        block(File("$basePath/$documentId"))
}
