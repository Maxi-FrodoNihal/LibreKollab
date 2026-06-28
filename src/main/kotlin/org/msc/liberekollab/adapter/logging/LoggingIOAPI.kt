package org.msc.liberekollab.adapter.logging

import org.msc.liberekollab.domain.port.IOAPI
import org.slf4j.LoggerFactory
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class LoggingIOAPI(private val delegate: IOAPI) : IOAPI {

    private val log = LoggerFactory.getLogger(LoggingIOAPI::class.java)

    override fun nextId(): String {
        val id = delegate.nextId()
        log.info("nextId() = $id")
        return id
    }

    override suspend fun upload(documentId: String, fileName: String, content: InputStream): String {
        log.info("upload(documentId=$documentId, fileName=$fileName)")
        return delegate.upload(documentId, fileName, content)
    }

    override suspend fun download(documentId: String): OutputStream {
        log.info("download(documentId=$documentId)")
        return delegate.download(documentId)
    }

    override suspend fun delete(documentId: String) {
        log.info("delete(documentId=$documentId)")
        delegate.delete(documentId)
    }

    override suspend fun ls(): List<String> {
        val result = delegate.ls()
        log.info("ls() = $result")
        return result
    }

    override suspend fun <T> withReadableFile(documentId: String, block: suspend (File) -> T): T {
        log.info("withReadableFile(documentId=$documentId)")
        return delegate.withReadableFile(documentId, block)
    }

    override suspend fun <T> withWritableFile(documentId: String, block: suspend (File) -> T): T {
        log.info("withWritableFile(documentId=$documentId)")
        return delegate.withWritableFile(documentId, block)
    }
}
