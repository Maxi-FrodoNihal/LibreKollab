package org.msc.librekollab

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Timeout
import org.msc.librekollab.adapter.mcp.McpServer
import org.msc.librekollab.domain.KollabAPI
import java.io.BufferedReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Timeout(30)
class McpHttpTransportTest {
    companion object {
        private const val HOST = "http://127.0.0.1:"
        private const val SESSION_HEADER = "Mcp-Session-Id"
        private const val INITIALIZE = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"transport-test","version":"1.0"}}}"""
        private const val INITIALIZED = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
        private const val LIST_TOOLS = """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""
        private const val LIST_DOCUMENTS = """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"list_documents","arguments":{}}}"""
        private const val DOCUMENT_ID = "transport-test.odt"
    }

    @Test
    fun `both transports initialize list tools and call the same API`() {
        runBlocking {
            val kollab = mockk<KollabAPI>()
            coEvery { kollab.listDocuments() } returns listOf(DOCUMENT_ID)
            val engine = McpServer(kollab).startHttp(0)
            try {
                val port = engine.engine.resolvedConnectors().single().port
                HttpClient.newHttpClient().use { client ->
                    val baseUrl = HOST + port
                    val httpUrl = URI.create("$baseUrl/mcp")
                    val initialized = post(client, httpUrl, INITIALIZE)
                    assertEquals(200, initialized.statusCode())
                    val sessionId = initialized.headers().firstValue(SESSION_HEADER).orElseThrow()
                    assertNotNull(result(initialized.body())["capabilities"])
                    assertEquals(202, post(client, httpUrl, INITIALIZED, sessionId).statusCode())

                    val sseRequest = HttpRequest.newBuilder(URI.create("$baseUrl/sse"))
                        .header("Accept", "text/event-stream").GET().build()
                    val sseResponse = client.send(sseRequest, HttpResponse.BodyHandlers.ofInputStream())
                    assertEquals(200, sseResponse.statusCode())
                    sseResponse.body().bufferedReader().use { reader ->
                        val postUrl = resolveSsePostUrl(URI.create("$baseUrl/sse"), readData(reader))
                        assertEquals(202, post(client, postUrl, INITIALIZE).statusCode())
                        assertNotNull(result(readData(reader))["capabilities"])
                        assertEquals(202, post(client, postUrl, INITIALIZED).statusCode())
                        assertEquals(202, post(client, postUrl, LIST_TOOLS).statusCode())
                        val sseTools = result(readData(reader))["tools"]?.jsonArray
                        val httpTools = result(post(client, httpUrl, LIST_TOOLS, sessionId).body())["tools"]?.jsonArray
                        assertNotNull(sseTools)
                        assertEquals(sseTools, httpTools)
                        assertTrue(sseTools.any { it.jsonObject["name"]?.jsonPrimitive?.content == "list_documents" })
                        assertEquals(202, post(client, postUrl, LIST_DOCUMENTS).statusCode())
                        val sseDocuments = result(readData(reader))
                        val httpDocuments = result(post(client, httpUrl, LIST_DOCUMENTS, sessionId).body())
                        assertEquals(sseDocuments, httpDocuments)
                        assertTrue(sseDocuments["content"]?.jsonArray?.first()?.jsonObject
                            ?.get("text")?.jsonPrimitive?.content.orEmpty().contains(DOCUMENT_ID))
                    }
                    val closeRequest = HttpRequest.newBuilder(httpUrl)
                        .header(SESSION_HEADER, sessionId)
                        .header("MCP-Protocol-Version", "2025-11-25").DELETE().build()
                    assertEquals(200, client.send(closeRequest, HttpResponse.BodyHandlers.ofString()).statusCode())
                    assertEquals(404, post(client, httpUrl, LIST_TOOLS, sessionId).statusCode())
                    assertEquals(200, post(client, httpUrl, INITIALIZE).statusCode())
                }
            } finally {
                engine.stop(0, 1000)
            }
        }
    }

    private fun resolveSsePostUrl(sseUrl: URI, endpoint: String): URI {
        if (endpoint.startsWith("?")) {
            return URI(sseUrl.scheme, sseUrl.authority, sseUrl.path, endpoint.removePrefix("?"), null)
        }
        return sseUrl.resolve(endpoint)
    }

    private fun post(client: HttpClient, uri: URI, body: String, sessionId: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        if (sessionId != null) {
            builder.header(SESSION_HEADER, sessionId)
            builder.header("MCP-Protocol-Version", "2025-11-25")
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun result(body: String): JsonObject {
        val response = Json.parseToJsonElement(body).jsonObject
        return assertNotNull(response["result"], body).jsonObject
    }

    private fun readData(reader: BufferedReader): String {
        return generateSequence { reader.readLine() }
            .first { it.startsWith("data:") }.removePrefix("data:").trim()
    }
}
