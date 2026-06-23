package org.msc.liberekollab

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.msc.liberekollab.adapter.MinioAdapter
import org.msc.liberekollab.logging.LoggingIOAPI
import org.msc.liberekollab.response.ListDocumentsResponse
import org.msc.liberekollab.response.UploadResponse
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.ServerSocket
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

        private val sharedStorage: MinioAdapter by lazy {
            MinioAdapter(
                host = minio.host,
                port = minio.getMappedPort(9000),
                accessKey = "minioadmin",
                secretKey = "minioadmin",
                bucket = "test-documents"
            )
        }

        private val port: Int by lazy { ServerSocket(0).use { it.localPort } }

        private val sharedServer: KtorServer by lazy {
            KtorServer(
                io = LoggingIOAPI(sharedStorage),
                kollab = LibereKollab(host = "unused", port = 0, storage = sharedStorage, workspacePath = System.getProperty("java.io.tmpdir"))
            ).apply { start(port) }
        }

        val sharedClient: HttpClient by lazy {
            HttpClient(CIO) {
                install(ContentNegotiation) { json() }
            }
        }

        @BeforeAll
        @JvmStatic
        fun setup() {
            sharedServer
        }

        @AfterAll
        @JvmStatic
        fun teardown() {
            sharedServer.stop()
            sharedClient.close()
        }
    }

    private val baseUrl get() = "http://localhost:$port"

    private fun odtBytes() = javaClass.getResourceAsStream("/test_hallo.odt")!!.readBytes()

    private suspend fun uploadHalloOdt(): String {
        val response = sharedClient.post("$baseUrl/documents/upload") {
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
    fun `health check returns healthy`() {
        runBlocking {
            val response = sharedClient.get("$baseUrl/documents/health")
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        }
    }

    @Test
    fun `upload returns documentId`() {
        runBlocking {
            val response = sharedClient.post("$baseUrl/documents/upload") {
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
    }

    @Test
    fun `list includes uploaded document`() {
        runBlocking {
            val documentId = uploadHalloOdt()

            val response = sharedClient.get("$baseUrl/documents")
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val body = Json.decodeFromString<ListDocumentsResponse>(response.bodyAsText())
            assertThat(body.documents).anyMatch { it.startsWith(documentId) }
        }
    }

    @Test
    fun `download returns uploaded document`() {
        runBlocking {
            val documentId = uploadHalloOdt()

            val response = sharedClient.get("$baseUrl/documents/$documentId")
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            assertThat(response.contentType()).isEqualTo(ContentType.Application.OctetStream)
            assertThat(response.readRawBytes()).isNotEmpty()
        }
    }

    @Test
    fun `delete removes document from list`() {
        runBlocking {
            val documentId = uploadHalloOdt()

            val deleteResponse = sharedClient.delete("$baseUrl/documents/$documentId")
            assertThat(deleteResponse.status).isEqualTo(HttpStatusCode.NoContent)

            val listResponse = sharedClient.get("$baseUrl/documents")
            val body = Json.decodeFromString<ListDocumentsResponse>(listResponse.bodyAsText())
            assertThat(body.documents).noneMatch { it.startsWith(documentId) }
        }
    }
}
