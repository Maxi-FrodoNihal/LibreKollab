package org.msc.liberekollab

import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.msc.liberekollab.controller.DocumentController
import org.msc.liberekollab.response.ListDocumentsResponse
import org.msc.liberekollab.response.UploadResponse
import org.msc.liberekollab.storage.MinioStorage
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Testcontainers
class DocumentControllerIT {

    companion object {
        @Container
        @JvmStatic
        val minio: GenericContainer<*> = GenericContainer("minio/minio:latest")
            .withEnv("MINIO_ROOT_USER", "minioadmin")
            .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
            .withCommand("server /data --console-address :9001")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000))
    }

    private fun withDocumentController(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val storage = MinioStorage(
            host = minio.host,
            port = minio.getMappedPort(9000),
            accessKey = "minioadmin",
            secretKey = "minioadmin",
            bucket = "test-${UUID.randomUUID()}"
        )
        application {
            install(ContentNegotiation) { json() }
            routing { DocumentController(storage).registerRoutes(this) }
        }
        block()
    }

    private fun odtBytes() = javaClass.getResourceAsStream("/test_hallo.odt")!!.readBytes()

    private suspend fun ApplicationTestBuilder.uploadHalloOdt(): String {
        val response = client.post("/documents/upload") {
            setBody(MultiPartFormDataContent(formData {
                append("file", odtBytes(), Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"test_hallo.odt\"")
                    append(HttpHeaders.ContentType, "application/octet-stream")
                })
            }))
        }
        return Json.decodeFromString<UploadResponse>(response.bodyAsText()).documentId
    }

    @Test
    fun `health check antwortet healthy`() = withDocumentController {
        val response = client.get("/documents/health")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `upload gibt documentId zurück`() = withDocumentController {
        val response = client.post("/documents/upload") {
            setBody(MultiPartFormDataContent(formData {
                append("file", odtBytes(), Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"test_hallo.odt\"")
                    append(HttpHeaders.ContentType, "application/octet-stream")
                })
            }))
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.decodeFromString<UploadResponse>(response.bodyAsText())
        assertTrue(body.documentId.isNotBlank())
    }

    @Test
    fun `ls listet hochgeladenes Dokument`() = withDocumentController {
        val documentId = uploadHalloOdt()

        val response = client.get("/documents")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.decodeFromString<ListDocumentsResponse>(response.bodyAsText())
        assertTrue(body.documents.any { it.startsWith(documentId) })
    }

    @Test
    fun `download gibt hochgeladenes Dokument zurück`() = withDocumentController {
        val documentId = uploadHalloOdt()

        val response = client.get("/documents/$documentId")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Application.OctetStream, response.contentType())
        assertTrue(response.readBytes().isNotEmpty())
    }

    @Test
    fun `delete entfernt Dokument aus der Liste`() = withDocumentController {
        val documentId = uploadHalloOdt()

        val deleteResponse = client.delete("/documents/$documentId")
        assertEquals(HttpStatusCode.NoContent, deleteResponse.status)

        val listResponse = client.get("/documents")
        val body = Json.decodeFromString<ListDocumentsResponse>(listResponse.bodyAsText())
        assertFalse(body.documents.any { it.startsWith(documentId) })
    }
}
