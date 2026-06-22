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
import org.assertj.core.api.Assertions.assertThat
import org.msc.liberekollab.controller.IOController
import org.msc.liberekollab.response.ListDocumentsResponse
import org.msc.liberekollab.response.UploadResponse
import org.msc.liberekollab.storage.MinioObject
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.Test

@Testcontainers
class IOControllerIT {

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
        val storage = MinioObject(
            host = minio.host,
            port = minio.getMappedPort(9000),
            accessKey = "minioadmin",
            secretKey = "minioadmin",
            bucket = "test-${UUID.randomUUID()}"
        )
        application {
            install(ContentNegotiation) { json() }
            routing { IOController(storage).registerRoutes(this) }
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
    fun `health check returns healthy`() = withDocumentController {
        val response = client.get("/documents/health")
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    }

    @Test
    fun `upload returns documentId`() = withDocumentController {
        val response = client.post("/documents/upload") {
            setBody(MultiPartFormDataContent(formData {
                append("file", odtBytes(), Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"test_hallo.odt\"")
                    append(HttpHeaders.ContentType, "application/octet-stream")
                })
            }))
        }
        assertThat(response.status).isEqualTo(HttpStatusCode.Created)
        val body = Json.decodeFromString<UploadResponse>(response.bodyAsText())
        assertThat(body.documentId).isNotBlank()
    }

    @Test
    fun `list includes uploaded document`() = withDocumentController {
        val documentId = uploadHalloOdt()

        val response = client.get("/documents")
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        val body = Json.decodeFromString<ListDocumentsResponse>(response.bodyAsText())
        assertThat(body.documents).anyMatch { it.startsWith(documentId) }
    }

    @Test
    fun `download returns uploaded document`() = withDocumentController {
        val documentId = uploadHalloOdt()

        val response = client.get("/documents/$documentId")
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        assertThat(response.contentType()).isEqualTo(ContentType.Application.OctetStream)
        assertThat(response.readBytes()).isNotEmpty()
    }

    @Test
    fun `delete removes document from list`() = withDocumentController {
        val documentId = uploadHalloOdt()

        val deleteResponse = client.delete("/documents/$documentId")
        assertThat(deleteResponse.status).isEqualTo(HttpStatusCode.NoContent)

        val listResponse = client.get("/documents")
        val body = Json.decodeFromString<ListDocumentsResponse>(listResponse.bodyAsText())
        assertThat(body.documents).noneMatch { it.startsWith(documentId) }
    }
}
