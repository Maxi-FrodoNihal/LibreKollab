package org.msc.librekollab

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.msc.librekollab.adapter.logging.LoggingKollabAPI
import org.msc.librekollab.adapter.mcp.McpServer
import org.msc.librekollab.adapter.libreoffice.TestKollab
import org.msc.librekollab.domain.model.SearchResult
import org.msc.librekollab.domain.model.text.MarkedText
import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID
import kotlin.test.Test

// Separate suite (own container, own TestKollab wired to PersistentTestDocumentComponent) reproducing a
// real-manuscript corruption: many sequential edit_text calls against the SAME long-lived, open document —
// as a real editing session does — rather than McpServerIT's per-call fresh-load-and-dispose cycle, which
// never accumulates the in-memory UNO/redline state the corruption depended on.
@Testcontainers
class McpServerLongSessionIT {

    companion object {
        val workspacePath: String = (System.getProperty("java.io.tmpdir") + "/librekollab-mcp-longsession-test")
            .also { Files.createDirectories(Paths.get(it)) }
        const val CONTAINER_WORKSPACE_PATH = "/workspace"

        @Container
        @JvmStatic
        val libreoffice: GenericContainer<*> = GenericContainer(
            ImageFromDockerfile("librekollab-libreoffice-test:latest", false)
                .withDockerfile(Paths.get("docker/libreoffice/Dockerfile"))
        )
            .withExposedPorts(2002)
            .withCreateContainerCmdModifier { cmd ->
                val binds = cmd.hostConfig?.binds ?: emptyArray()
                cmd.hostConfig?.withBinds(*binds, com.github.dockerjava.api.model.Bind(workspacePath, com.github.dockerjava.api.model.Volume(CONTAINER_WORKSPACE_PATH)))
            }

        private val sharedKollab: TestKollab by lazy {
            TestKollab.viaSocketPersistent(
                host = libreoffice.host,
                port = libreoffice.getMappedPort(2002),
                basePath = workspacePath,
                containerWorkspacePath = CONTAINER_WORKSPACE_PATH
            )
        }

        private val sharedMcpServer: McpServer by lazy {
            McpServer(kollab = LoggingKollabAPI(sharedKollab))
        }

        private val clientToServerOut = PipedOutputStream()
        private val clientToServerIn = PipedInputStream(clientToServerOut, 65536)
        private val serverToClientOut = PipedOutputStream()
        private val serverToClientIn = PipedInputStream(serverToClientOut, 65536)
        private val errorOut = PipedOutputStream()
        private val errorIn = PipedInputStream(errorOut)

        private val testScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private lateinit var serverJob: Job

        private val sharedClient: Client by lazy { Client(Implementation(name = "test-client", version = "1.0.0")) }

        @BeforeAll
        @JvmStatic
        fun setup(): Unit = runBlocking {
            serverJob = testScope.launch {
                sharedMcpServer.connectTo(
                    StdioServerTransport(
                        clientToServerIn.asSource().buffered(),
                        serverToClientOut.asSink().buffered()
                    ) {}
                )
            }
            sharedClient.connect(
                StdioClientTransport(
                    serverToClientIn.asSource().buffered(),
                    clientToServerOut.asSink().buffered(),
                    errorIn.asSource().buffered()
                )
            )
        }

        @AfterAll
        @JvmStatic
        fun teardown() {
            clientToServerOut.close()
            runBlocking { serverJob.join() }
            serverToClientOut.close()
            runBlocking { sharedClient.close() }
            testScope.cancel()
            sharedKollab.close()
        }
    }

    private fun upload(resourceName: String): String {
        val fileName = "${UUID.randomUUID()}_$resourceName"
        File("$workspacePath/$fileName").writeBytes(javaClass.getResourceAsStream("/$resourceName")!!.readBytes())
        return fileName
    }

    private suspend fun tool(name: String, args: JsonObject = buildJsonObject {}): String {
        val result = sharedClient.callTool(name, args)
        return (result.content.first() as TextContent).text
    }

    private suspend fun fixTypo(documentId: String, paragraphIndex: Int, charStart: Int, charEnd: Int, newText: String) {
        tool("edit_text", buildJsonObject {
            put("documentId", documentId)
            put("anchorText", "irrelevant")
            put("anchorParagraphIndex", paragraphIndex)
            put("anchorCharStart", charStart)
            put("anchorCharEnd", charEnd)
            put("newText", newText)
        })
    }

    @Test
    fun `T01 six sequential edit_text fixes on one long-lived open document produce no duplicated text`() {
        runBlocking {
            val documentId = upload("test_long_session.odt")

            fixTypo(documentId, 0, 17, 45, "Ausschnitt eines bedrohlichen")
            fixTypo(documentId, 1, 5, 17, "irisierenden")
            fixTypo(documentId, 2, 36, 43, "direkt")
            fixTypo(documentId, 3, 19, 26, "spüren")
            fixTypo(documentId, 4, 16, 24, "beiläufig")
            fixTypo(documentId, 5, 33, 40, "Argwohn")

            val after = Json.decodeFromString(MarkedText.serializer(), tool("get_text", buildJsonObject {
                put("documentId", documentId)
                put("changeStatus", "AFTER")
            }))

            assertThat(after.text.trim().lines().map { it.trim() }).containsExactly(
                "Er sah nur einen Ausschnitt eines bedrohlichen Kunstwerks der Natur, welches sich mit Sicherheit über weit mehr als nur den Oberschenkel erstreckte.",
                "Jene irisierenden Narben ließen eigentlich nur einen Schluss zu und der Jungmagister schluckte.",
                "Ann sah Lonn mit aufwallender Angst direkt in die Augen.",
                "Er hätte es früher spüren müssen, doch es war zu spät.",
                "Sie hatte seine beiläufig erwähnten Worte nicht vergessen.",
                "Auf ihrem Gesicht zeichnete sich Argwohn ab, während sie schwieg."
            )
        }
    }

    @Test
    fun `T02 re-searching for a fresh anchor between two same-paragraph edits avoids stale-offset corruption`() {
        // A second edit_text call into a paragraph already touched by an earlier edit in the same session
        // must use an anchor re-fetched (e.g. via search) AFTER that earlier edit, not one computed before
        // it — the earlier edit shifts every offset after it in that paragraph. Reusing a stale offset here
        // reproduces the exact corruption seen with a real manuscript: the second edit lands mid-word,
        // splicing new text into the middle of e.g. "Oberschenkel erstreckte" while leaving the original,
        // untouched anchor text further down completely intact and duplicated.
        runBlocking {
            val documentId = upload("test_two_edits_same_paragraph.odt")

            fixTypo(documentId, 0, 105, 133, "Ausschnitt eines bedrohlichen")

            val searchJson = tool("search", buildJsonObject {
                put("documentId", documentId)
                put("searchText", "Jene erisierenden Narben")
            })
            val searchResult = Json.decodeFromString(SearchResult.serializer(), searchJson)
            assertThat(searchResult.elements).hasSize(1)
            val freshAnchor = searchResult.elements.single().textAnchor

            fixTypo(documentId, freshAnchor.paragraphIndex, freshAnchor.charStart, freshAnchor.charEnd, "Jene irisierenden Narben")

            val after = Json.decodeFromString(MarkedText.serializer(), tool("get_text", buildJsonObject {
                put("documentId", documentId)
                put("changeStatus", "AFTER")
            }))

            assertThat(after.text.trim()).isEqualTo(
                "Lonn war im Begriff etwas zu sagen, er verkniff sich aber seine Vermutung über das Mal. " +
                    "Er sah nur einen Ausschnitt eines bedrohlichen Kunstwerks der Natur, welches sich mit " +
                    "Sicherheit über weit mehr als nur den Oberschenkel erstreckte. Jene irisierenden Narben " +
                    "ließen eigentlich nur einen Schluss zu und der Jungmagister schluckte."
            )
        }
    }
}
