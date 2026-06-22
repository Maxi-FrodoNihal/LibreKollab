package org.msc.liberekollab.controller

import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.toByteArray
import org.msc.liberekollab.abstrakt.IOAPI
import org.msc.liberekollab.response.ListDocumentsResponse
import org.msc.liberekollab.response.UploadResponse
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class IOController(private val storage: IOAPI) {

    companion object {
        private const val DEFAULT_FILE_NAME = "document"
    }

    fun registerRoutes(routing: Routing) {
        routing.get("/documents/health", {
            tags = listOf("Documents")
            summary = "Health check"
            response { code(HttpStatusCode.OK) { description = "Service is healthy" } }
        }) { healthCheck() }

        routing.post("/documents/upload", {
            tags = listOf("Documents")
            summary = "Upload a document"
            request {
                body<ByteArray> { description = "ODT file (multipart/form-data)" }
            }
            response { code(HttpStatusCode.Created) { body<UploadResponse>() } }
        }) { upload() }

        routing.get("/documents", {
            tags = listOf("Documents")
            summary = "List all document IDs"
            response { code(HttpStatusCode.OK) { body<ListDocumentsResponse>() } }
        }) { list() }

        routing.get("/documents/{id}", {
            tags = listOf("Documents")
            summary = "Download a document"
            request { pathParameter<String>("id") { } }
            response {
                code(HttpStatusCode.OK) {
                    body<ByteArray> { description = "Raw document bytes (application/octet-stream)" }
                }
            }
        }) { download() }

        routing.delete("/documents/{id}", {
            tags = listOf("Documents")
            summary = "Delete a document"
            request { pathParameter<String>("id") { } }
            response { code(HttpStatusCode.NoContent) { description = "Document deleted" } }
        }) { delete() }
    }

    private suspend fun RoutingContext.upload() {
        val multipart = call.receiveMultipart()
        val id = storage.nextId()
        var fileName = ""
        var fileStream = ByteArrayInputStream(ByteArray(0))

        multipart.forEachPart { part ->
            if (part is PartData.FileItem) {
                fileName = part.resolveFileName()
                fileStream = ByteArrayInputStream(part.provider().toByteArray())
            }
            part.dispose()
        }

        val documentId = storage.upload(id, fileName, fileStream)
        call.respond(HttpStatusCode.Created, UploadResponse(documentId))
    }

    private fun PartData.FileItem.resolveFileName(): String = originalFileName ?: DEFAULT_FILE_NAME

    private suspend fun RoutingContext.list() {
        val documents = storage.ls()
        call.respond(HttpStatusCode.OK, ListDocumentsResponse(documents))
    }

    private suspend fun RoutingContext.download() {
        val id = verifyForBadRequest("id") ?: return
        val out = storage.download(id) as ByteArrayOutputStream
        call.respondBytes(out.toByteArray(), ContentType.Application.OctetStream)
    }

    private suspend fun RoutingContext.delete() {
        val id = verifyForBadRequest("id") ?: return
        storage.delete(id)
        call.respond(HttpStatusCode.NoContent)
    }

    private suspend fun RoutingContext.verifyForBadRequest(paramName: String): String? {
        val value = call.parameters[paramName]
        if (value == null) call.respond(HttpStatusCode.BadRequest)
        return value
    }

    private suspend fun RoutingContext.healthCheck() {
        call.respond("healthy")
    }
}
