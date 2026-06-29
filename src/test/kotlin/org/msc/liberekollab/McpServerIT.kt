package org.msc.liberekollab

import com.github.dockerjava.api.model.Bind
import com.github.dockerjava.api.model.Volume
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
import kotlinx.datetime.LocalDateTime
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.msc.liberekollab.adapter.logging.LoggingKollabAPI
import org.msc.liberekollab.adapter.mcp.McpServer
import org.msc.liberekollab.adapter.libreoffice.TestKollab
import org.msc.liberekollab.domain.model.Comment
import org.msc.liberekollab.domain.model.TextAnchor
import org.msc.liberekollab.domain.model.change.Change
import org.msc.liberekollab.domain.model.change.ChangeAction
import org.msc.liberekollab.domain.model.text.MarkIndex
import org.msc.liberekollab.domain.model.text.MarkedText
import org.msc.liberekollab.domain.model.text.properties.BoldProperty
import org.msc.liberekollab.domain.model.text.properties.ItalicProperty
import org.msc.liberekollab.domain.model.text.properties.StrikethroughProperty
import org.msc.liberekollab.domain.model.text.properties.UnderlineProperty
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

@Testcontainers
class McpServerIT {

    companion object {
        val workspacePath: String = (System.getProperty("java.io.tmpdir") + "/liberekollab-mcp-test")
            .also { Files.createDirectories(Paths.get(it)) }
        const val CONTAINER_WORKSPACE_PATH = "/workspace"

        @Container
        @JvmStatic
        val libreoffice: GenericContainer<*> = GenericContainer(
            ImageFromDockerfile("liberekollab-libreoffice-test:latest", false)
                .withDockerfile(Paths.get("docker/libreoffice/Dockerfile"))
        )
            .withExposedPorts(2002)
            .withCreateContainerCmdModifier { cmd ->
                val binds = cmd.hostConfig?.binds ?: emptyArray()
                cmd.hostConfig?.withBinds(*binds, Bind(workspacePath, Volume(CONTAINER_WORKSPACE_PATH)))
            }

        private val sharedKollab: TestKollab by lazy {
            TestKollab.viaSocket(
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
            // 1. Close client→server pipe: server reader gets EOF → session closes naturally via onClose.
            clientToServerOut.close()
            // 2. Wait for connectTo to finish its finally { session.close() } block.
            //    Only after this is the server guaranteed to have stopped writing to serverToClientOut.
            runBlocking { serverJob.join() }
            // 3. Now close server→client pipe: client reader gets EOF.
            serverToClientOut.close()
            // 4. Client can close cleanly — its reader has EOF, no waiting for a server response.
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

    @Test
    fun `T01 list_documents includes uploaded document`() {
        runBlocking {
            val documentId = upload("test_hallo.odt")
            val result = tool("list_documents")
            assertThat(result).contains(documentId)
        }
    }

    @Test
    fun `T02 get_text returns document text`() {
        runBlocking {
            val documentId = upload("test_hallo.odt")
            val markedText = Json.decodeFromString(MarkedText.serializer(), tool("get_text", buildJsonObject { put("documentId", documentId) }))
            assertThat(markedText.text).isEqualTo("Hallo test")
        }
    }

    @Test
    fun `T03 editText replaces text range and changeStatus variants reflect the change`() {
        runBlocking {
            val documentId = upload("test_hallo.odt")
            tool("edit_text", buildJsonObject {
                put("documentId", documentId)
                put("anchorText", "Hallo")
                put("anchorParagraphIndex", 0)
                put("anchorCharStart", 0)
                put("anchorCharEnd", 5)
                put("newText", "Tschüss")
            })

            val after = Json.decodeFromString(MarkedText.serializer(), tool("get_text", buildJsonObject {
                put("documentId", documentId)
                put("changeStatus", "AFTER")
            }))
            assertThat(after.text.trim()).isEqualTo("Tschüss test")

            val before = Json.decodeFromString(MarkedText.serializer(), tool("get_text", buildJsonObject {
                put("documentId", documentId)
                put("changeStatus", "BEFORE")
            }))
            assertThat(before.text.trim()).isEqualTo("Hallo test")
        }
    }

    @Test
    fun `T04 get_changes returns tracked changes after edit`() {
        runBlocking {
            val documentId = upload("test_hallo.odt")
            tool("edit_text", buildJsonObject {
                put("documentId", documentId)
                put("anchorText", "Hallo")
                put("anchorParagraphIndex", 0)
                put("anchorCharStart", 0)
                put("anchorCharEnd", 5)
                put("newText", "Tschüss")
                put("author", "Max Mustermann")
            })

            val json = tool("get_changes", buildJsonObject { put("documentId", documentId) })
            val changes = Json.decodeFromString(ListSerializer(Change.serializer()), json)
            assertThat(changes).hasSize(2)
            assertThat(changes.map { it.action }).containsExactlyInAnyOrder(ChangeAction.DELETE, ChangeAction.INSERT)
            assertThat(changes.first { it.action == ChangeAction.DELETE }.text).isEqualTo("Hallo")
            assertThat(changes.first { it.action == ChangeAction.DELETE }.author).isEqualTo("Max Mustermann")
            assertThat(changes.first { it.action == ChangeAction.INSERT }.text).isEqualTo("Tschüss")
            assertThat(changes.first { it.action == ChangeAction.INSERT }.author).isEqualTo("Max Mustermann")
        }
    }

    @Test
    fun `T05 get_page_count returns correct page count`() {
        runBlocking {
            val documentId = upload("test_three_pages.odt")
            val count = tool("get_page_count", buildJsonObject { put("documentId", documentId) })
            assertThat(count).isEqualTo("3")
        }
    }

    @Test
    fun `T06 get_chapters returns all chapter headings`() {
        runBlocking {
            val documentId = upload("test_two_chapters.odt")
            val chapters = tool("get_chapters", buildJsonObject { put("documentId", documentId) })
            assertThat(chapters).isEqualTo("Kapitel 1 Test Überschrift Hallo\nKapitel 2 Test Überschrift Bla")
        }
    }

    @Test
    fun `T07 get_text_by_pages returns text of given page`() {
        runBlocking {
            val documentId = upload("test_three_pages.odt")
            val markedText = Json.decodeFromString(MarkedText.serializer(), tool("get_text_by_pages", buildJsonObject {
                put("documentId", documentId)
                put("fromPage", 2)
                put("toPage", 2)
            }))
            assertThat(markedText.text.trim()).isEqualTo("Text Seite 2")
        }
    }

    @Test
    fun `T08 get_text_by_chapter returns text of given chapter`() {
        runBlocking {
            val documentId = upload("test_two_chapters.odt")
            val markedText = Json.decodeFromString(MarkedText.serializer(), tool("get_text_by_chapter", buildJsonObject {
                put("documentId", documentId)
                put("chapter", "Kapitel 1 Test Überschrift Hallo")
            }))
            assertThat(markedText.text.trim()).isEqualTo("Das ist der Text für Kapitel 1")
        }
    }

    @Test
    fun `T09 add_comment inserts comment at text anchor`() {
        runBlocking {
            val documentId = upload("test_add_comment.odt")
            tool("add_comment", buildJsonObject {
                put("documentId", documentId)
                put("commentText", "Test Kommentar")
                put("author", "Max Mustermann")
                put("anchorText", "add a comment")
                put("anchorParagraphIndex", 0)
                put("anchorCharStart", 7)
                put("anchorCharEnd", 20)
            })

            val json = tool("get_comments", buildJsonObject { put("documentId", documentId) })
            val comments = Json.decodeFromString(ListSerializer(Comment.serializer()), json)
            assertThat(comments).hasSize(1)
            assertThat(comments[0].content).isEqualTo("Test Kommentar")
            assertThat(comments[0].author).isEqualTo("Max Mustermann")
            assertThat(comments[0].anchor).isEqualTo(TextAnchor("add a comment", 0, 7, 20))
            assertThat(comments[0].dateTime.year).isGreaterThanOrEqualTo(2026)
        }
    }

    @Test
    fun `T10 get_comment returns single comment by id`() {
        runBlocking {
            val documentId = upload("test_three_comments.odt")
            val allJson = tool("get_comments", buildJsonObject { put("documentId", documentId) })
            val all = Json.decodeFromString(ListSerializer(Comment.serializer()), allJson)

            val commentId = all[1].id
            val singleJson = tool("get_comment", buildJsonObject {
                put("documentId", documentId)
                put("commentId", commentId)
            })
            val comment = Json.decodeFromString(Comment.serializer(), singleJson)
            assertThat(comment).isEqualTo(all[1])
        }
    }

    @Test
    fun `T11 update_comment changes text`() {
        runBlocking {
            val documentId = upload("test_with_comment.odt")
            val beforeJson = tool("get_comments", buildJsonObject { put("documentId", documentId) })
            val before = Json.decodeFromString(ListSerializer(Comment.serializer()), beforeJson)
            val commentId = before[0].id

            tool("update_comment", buildJsonObject {
                put("documentId", documentId)
                put("commentId", commentId)
                put("newText", "Neuer Kommentartext")
            })

            val afterJson = tool("get_comments", buildJsonObject { put("documentId", documentId) })
            val after = Json.decodeFromString(ListSerializer(Comment.serializer()), afterJson)
            assertThat(after[0].content).isEqualTo("Neuer Kommentartext")
            assertThat(after[0].dateTime).isGreaterThanOrEqualTo(before[0].dateTime)
        }
    }

    @Test
    fun `T12 delete_comment removes comment from document`() {
        runBlocking {
            val documentId = upload("test_with_comment.odt")
            val beforeJson = tool("get_comments", buildJsonObject { put("documentId", documentId) })
            val before = Json.decodeFromString(ListSerializer(Comment.serializer()), beforeJson)
            assertThat(before).hasSize(1)
            val commentId = before[0].id

            tool("delete_comment", buildJsonObject {
                put("documentId", documentId)
                put("commentId", commentId)
            })

            val afterJson = tool("get_comments", buildJsonObject { put("documentId", documentId) })
            val after = Json.decodeFromString(ListSerializer(Comment.serializer()), afterJson)
            assertThat(after).isEmpty()
        }
    }

    @Test
    fun `T13 get_text extracts text formatting properties`() {
        runBlocking {
            val documentId = upload("test_text_properties.odt")
            val markedText = Json.decodeFromString(MarkedText.serializer(), tool("get_text", buildJsonObject { put("documentId", documentId) }))

            assertThat(markedText.text).isEqualTo(
                "Das ist ein fetter Text\nDas ist ein kursiver Text\nDas ist ein unterstrichener Text\nDas ist ein durchgestrichener Text"
            )
            assertThat(markedText.properties).hasSize(4)
            assertThat(markedText.properties[0]).isInstanceOf(BoldProperty::class.java)
            assertThat(markedText.properties[0].markIndex).isEqualTo(MarkIndex(0, 0, 23))
            assertThat(markedText.properties[1]).isInstanceOf(ItalicProperty::class.java)
            assertThat(markedText.properties[1].markIndex).isEqualTo(MarkIndex(1, 0, 25))
            assertThat(markedText.properties[2]).isInstanceOf(UnderlineProperty::class.java)
            assertThat(markedText.properties[2].markIndex).isEqualTo(MarkIndex(2, 0, 32))
            assertThat(markedText.properties[3]).isInstanceOf(StrikethroughProperty::class.java)
            assertThat(markedText.properties[3].markIndex).isEqualTo(MarkIndex(3, 0, 34))
        }
    }

    @Test
    fun `T14 edit_text inserts text with formatting property`() {
        runBlocking {
            val documentId = upload("test_hallo.odt")
            tool("edit_text", buildJsonObject {
                put("documentId", documentId)
                put("anchorText", "Hallo")
                put("anchorParagraphIndex", 0)
                put("anchorCharStart", 0)
                put("anchorCharEnd", 5)
                put("newText", "Fett")
                put("newTextProperties", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "bold")
                        put("markIndex", buildJsonObject { put("paragraphIndex", 0); put("from", 0); put("to", 4) })
                    })
                })
            })

            val result = Json.decodeFromString(MarkedText.serializer(), tool("get_text", buildJsonObject {
                put("documentId", documentId)
                put("changeStatus", "AFTER")
            }))
            assertThat(result.properties).hasSize(1)
            assertThat(result.properties[0]).isInstanceOf(BoldProperty::class.java)
            assertThat(result.properties[0].markIndex).isEqualTo(MarkIndex(0, 0, 4))
        }
    }

    @Test
    fun `T15 edit_text removes formatting when replacing with plain text`() {
        runBlocking {
            val documentId = upload("test_text_properties.odt")
            tool("edit_text", buildJsonObject {
                put("documentId", documentId)
                put("anchorText", "Das ist ein fetter Text")
                put("anchorParagraphIndex", 0)
                put("anchorCharStart", 0)
                put("anchorCharEnd", 23)
                put("newText", "Das ist ein fetter Text")
            })

            val result = Json.decodeFromString(MarkedText.serializer(), tool("get_text", buildJsonObject {
                put("documentId", documentId)
                put("changeStatus", "AFTER")
            }))
            assertThat(result.properties.filterIsInstance<BoldProperty>()).isEmpty()
        }
    }

    @Test
    fun `T16 get_changes returns author and full text for replacement with mixed formatting`() {
        // LibreOffice stores the whole replacement as one INSERT redline regardless of
        // internal formatting — "Tschüss Welt" is one tracked change, not two.
        runBlocking {
            val documentId = upload("test_hallo.odt")

            tool("edit_text", buildJsonObject {
                put("documentId", documentId)
                put("anchorText", "Hallo")
                put("anchorParagraphIndex", 0)
                put("anchorCharStart", 0)
                put("anchorCharEnd", 5)
                put("newText", "Tschüss Welt")
                put("newTextProperties", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "bold")
                        put("markIndex", buildJsonObject { put("paragraphIndex", 0); put("from", 8); put("to", 12) })
                    })
                })
                put("author", "Test Author")
            })

            val json = tool("get_changes", buildJsonObject { put("documentId", documentId) })
            val changes = Json.decodeFromString(ListSerializer(Change.serializer()), json)
            assertThat(changes).hasSize(2)
            assertThat(changes.first { it.action == ChangeAction.DELETE }.text).isEqualTo("Hallo")
            assertThat(changes.first { it.action == ChangeAction.INSERT }.text).isEqualTo("Tschüss Welt")
            assertThat(changes).allMatch { it.author == "Test Author" }
        }
    }

    @Test
    fun `T17 comment paragraph index is consistent between add_comment and get_comments`() {
        runBlocking {
            val documentId = upload("test_text_properties.odt")
            tool("add_comment", buildJsonObject {
                put("documentId", documentId)
                put("commentText", "Paragraph index round-trip")
                put("author", "Test")
                put("anchorText", "kursiver")
                put("anchorParagraphIndex", 1)
                put("anchorCharStart", 12)
                put("anchorCharEnd", 20)
            })

            val json = tool("get_comments", buildJsonObject { put("documentId", documentId) })
            val comments = Json.decodeFromString(ListSerializer(Comment.serializer()), json)
            assertThat(comments).hasSize(1)
            assertThat(comments[0].anchor).isEqualTo(TextAnchor("kursiver", 1, 12, 20))
        }
    }

    @Test
    fun `T18 get_comments returns all comments with correct data`() {
        runBlocking {
            val documentId = upload("test_three_comments.odt")
            val json = tool("get_comments", buildJsonObject { put("documentId", documentId) })
            val comments = Json.decodeFromString(ListSerializer(Comment.serializer()), json)

            val first = LocalDateTime(2026, 6, 19, 12, 26, 21)
            val last  = LocalDateTime(2026, 6, 19, 12, 27,  8)

            assertThat(comments).hasSize(3)

            assertThat(comments[0].author).isEqualTo("Unknown Author")
            assertThat(comments[0].content).isEqualTo("Text1 wird groß geschrieben")
            assertThat(comments[0].dateTime).isBetween(first, last)
            assertThat(comments[0].anchor).isEqualTo(TextAnchor("text1", 2, 8, 13))

            assertThat(comments[1].author).isEqualTo("Unknown Author")
            assertThat(comments[1].content).isEqualTo("Verstehe ich nicht")
            assertThat(comments[1].dateTime).isBetween(first, last)
            assertThat(comments[1].anchor).isEqualTo(TextAnchor("text2", 6, 8, 13))

            assertThat(comments[2].author).isEqualTo("Unknown Author")
            assertThat(comments[2].content).isEqualTo("Das ist korrekt")
            assertThat(comments[2].dateTime).isBetween(first, last)
            assertThat(comments[2].anchor).isEqualTo(TextAnchor("Text3", 10, 8, 13))
        }
    }

    @Test
    fun `T19 get_changes returns all inserts when pure insert has mixed formatting`() {
        // Pure insert (charStart==charEnd) with plain + bold text.
        // LibreOffice splits the insertion into separate tracked changes per format run;
        // getChanges must return all of them (the Format redline must not reset accumulation state).
        runBlocking {
            val documentId = upload("test_hallo.odt")

            tool("edit_text", buildJsonObject {
                put("documentId", documentId)
                put("anchorText", "Hallo")
                put("anchorParagraphIndex", 0)
                put("anchorCharStart", 5)
                put("anchorCharEnd", 5)
                put("newText", " Dann: Dunkelheit.")
                put("newTextProperties", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "bold")
                        put("markIndex", buildJsonObject { put("paragraphIndex", 0); put("from", 7); put("to", 18) })
                    })
                })
                put("author", "Test Author")
            })

            val json = tool("get_changes", buildJsonObject { put("documentId", documentId) })
            val changes = Json.decodeFromString(ListSerializer(Change.serializer()), json)
            val insertChanges = changes.filter { it.action == ChangeAction.INSERT }
            assertThat(changes.none { it.action == ChangeAction.DELETE }).isTrue()
            assertThat(insertChanges.size).isGreaterThanOrEqualTo(2)
            assertThat(insertChanges.joinToString("") { it.text }).isEqualTo(" Dann: Dunkelheit.")
            assertThat(insertChanges).allMatch { it.author == "Test Author" }
        }
    }
}
