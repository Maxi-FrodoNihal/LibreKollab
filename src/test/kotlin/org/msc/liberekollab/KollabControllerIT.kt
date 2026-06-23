package org.msc.liberekollab

import com.github.dockerjava.api.model.Bind
import com.github.dockerjava.api.model.Volume
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.msc.liberekollab.adapter.MinioAdapter
import org.msc.liberekollab.logging.LoggingIOAPI
import org.msc.liberekollab.logging.LoggingKollabAPI
import org.msc.liberekollab.model.Comment
import org.msc.liberekollab.model.TextAnchor
import org.msc.liberekollab.model.change.ChangeAction
import org.msc.liberekollab.model.text.MarkIndex
import org.msc.liberekollab.model.text.MarkedText
import org.msc.liberekollab.model.text.properties.BoldProperty
import org.msc.liberekollab.model.text.properties.ItalicProperty
import org.msc.liberekollab.model.text.properties.StrikethroughProperty
import org.msc.liberekollab.model.text.properties.UnderlineProperty
import org.msc.liberekollab.request.AddCommentRequest
import org.msc.liberekollab.request.EditTextRequest
import org.msc.liberekollab.request.UpdateCommentRequest
import org.msc.liberekollab.response.ChangesResponse
import org.msc.liberekollab.response.ChaptersResponse
import org.msc.liberekollab.response.CommentsResponse
import org.msc.liberekollab.response.PageCountResponse
import org.msc.liberekollab.response.TextResponse
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.ByteArrayInputStream
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test

@Testcontainers
class KollabControllerIT {

    companion object {
        val workspacePath: String = (System.getProperty("java.io.tmpdir") + "/liberekollab-test-workspace")
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
            .withReuse(true)

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
                kollab = LoggingKollabAPI(LibereKollab(
                    host = libreoffice.host,
                    port = libreoffice.getMappedPort(2002),
                    storage = sharedStorage,
                    workspacePath = workspacePath,
                    containerWorkspacePath = CONTAINER_WORKSPACE_PATH
                ))
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

    private fun upload(resourceName: String): String {
        val bytes = javaClass.getResourceAsStream("/$resourceName")!!.readBytes()
        return runBlocking { sharedStorage.upload(sharedStorage.nextId(), resourceName, ByteArrayInputStream(bytes)) }
    }

    @Test
    fun `health check returns healthy`() {
        runBlocking {
            val response = sharedClient.get("$baseUrl/kollab/health")
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        }
    }

    @Test
    fun `editText replaces text range in document`() {
        runBlocking {
            val documentId = upload("test_hallo.odt")
            val anchor = TextAnchor("Hallo", 0, 0, 5)

            val editResponse = sharedClient.patch("$baseUrl/kollab/text/$documentId") {
                contentType(ContentType.Application.Json)
                setBody(EditTextRequest(anchor, MarkedText("Tschüss")))
            }
            assertThat(editResponse.status).isEqualTo(HttpStatusCode.OK)

            val after = sharedClient.get("$baseUrl/kollab/text/$documentId?changeStatus=AFTER").body<TextResponse>()
            assertThat(after.text.text.trim()).isEqualTo("Tschüss test")

            val before = sharedClient.get("$baseUrl/kollab/text/$documentId?changeStatus=BEFORE").body<TextResponse>()
            assertThat(before.text.text.trim()).isEqualTo("Hallo test")
        }
    }

    @Test
    fun `getChanges returns tracked changes`() {
        runBlocking {
            val documentId = upload("test_hallo.odt")
            sharedClient.patch("$baseUrl/kollab/text/$documentId") {
                contentType(ContentType.Application.Json)
                setBody(EditTextRequest(TextAnchor("Hallo", 0, 0, 5), MarkedText("Tschüss")))
            }

            val body = sharedClient.get("$baseUrl/kollab/text/$documentId/changes").body<ChangesResponse>()
            assertThat(body.changes).hasSize(2)
            assertThat(body.changes.map { it.action }).containsExactlyInAnyOrder(ChangeAction.DELETE, ChangeAction.INSERT)
            assertThat(body.changes.first { it.action == ChangeAction.DELETE }.text).isEqualTo("Hallo")
            assertThat(body.changes.first { it.action == ChangeAction.INSERT }.text).isEqualTo("Tschüss")
        }
    }

    @Test
    fun `editText returns 400 for invalid anchor`() {
        runBlocking {
            val documentId = upload("test_hallo.odt")
            val response = sharedClient.patch("$baseUrl/kollab/text/$documentId") {
                contentType(ContentType.Application.Json)
                setBody(EditTextRequest(TextAnchor("Hallo", 99, 0, 5), MarkedText("Tschüss")))
            }
            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
        }
    }

    @Test
    fun `getText extracts text from odt file`() {
        runBlocking {
            val documentId = upload("test_hallo.odt")
            val body = sharedClient.get("$baseUrl/kollab/text/$documentId").body<TextResponse>()
            assertThat(body.text.text).isEqualTo("Hallo test")
        }
    }

    @Test
    fun `getText extracts text formatting properties`() {
        runBlocking {
            val documentId = upload("test_text_properties.odt")
            val markedText = sharedClient.get("$baseUrl/kollab/text/$documentId").body<TextResponse>().text

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
    fun `editText inserts text with formatting property`() {
        runBlocking {
            val documentId = upload("test_hallo.odt")
            sharedClient.patch("$baseUrl/kollab/text/$documentId") {
                contentType(ContentType.Application.Json)
                setBody(EditTextRequest(TextAnchor("Hallo", 0, 0, 5), MarkedText("Fett", listOf(BoldProperty(MarkIndex(0, 0, 4))))))
            }

            val result = sharedClient.get("$baseUrl/kollab/text/$documentId?changeStatus=AFTER").body<TextResponse>()
            assertThat(result.text.properties).hasSize(1)
            assertThat(result.text.properties[0]).isInstanceOf(BoldProperty::class.java)
            assertThat(result.text.properties[0].markIndex).isEqualTo(MarkIndex(0, 0, 4))
        }
    }

    @Test
    fun `editText removes formatting when replacing with plain text`() {
        runBlocking {
            val documentId = upload("test_text_properties.odt")
            sharedClient.patch("$baseUrl/kollab/text/$documentId") {
                contentType(ContentType.Application.Json)
                setBody(EditTextRequest(TextAnchor("Das ist ein fetter Text", 0, 0, 23), MarkedText("Das ist ein fetter Text")))
            }

            val result = sharedClient.get("$baseUrl/kollab/text/$documentId?changeStatus=AFTER").body<TextResponse>()
            assertThat(result.text.properties.filterIsInstance<BoldProperty>()).isEmpty()
        }
    }

    @Test
    fun `getPageCount returns correct page count`() {
        runBlocking {
            val documentId = upload("test_three_pages.odt")
            val body = sharedClient.get("$baseUrl/kollab/text/$documentId/pagecount").body<PageCountResponse>()
            assertThat(body.pageCount).isEqualTo(3)
        }
    }

    @Test
    fun `getChapters returns all chapter headings`() {
        runBlocking {
            val documentId = upload("test_two_chapters.odt")
            val body = sharedClient.get("$baseUrl/kollab/text/$documentId/chapters").body<ChaptersResponse>()
            assertThat(body.chapters).containsExactly(
                "Kapitel 1 Test Überschrift Hallo",
                "Kapitel 2 Test Überschrift Bla"
            )
        }
    }

    @Test
    fun `getTextByPages returns text of given page`() {
        runBlocking {
            val documentId = upload("test_three_pages.odt")
            val body = sharedClient.get("$baseUrl/kollab/text/$documentId/pages/2/2").body<TextResponse>()
            assertThat(body.text.text.trim()).isEqualTo("Text Seite 2")
        }
    }

    @Test
    fun `getTextByChapter returns text of given chapter`() {
        runBlocking {
            val documentId = upload("test_two_chapters.odt")
            val chapter = "Kapitel 1 Test Überschrift Hallo"
            val body = sharedClient.get {
                url("$baseUrl/kollab/text/$documentId/chapters")
                url.appendPathSegments(chapter)
            }.body<TextResponse>()
            assertThat(body.text.text.trim()).isEqualTo("Das ist der Text für Kapitel 1")
        }
    }

    @Test
    fun `addComment inserts comment at text anchor`() {
        runBlocking {
            val documentId = upload("test_add_comment.odt")
            val anchor = TextAnchor("add a comment", 0, 7, 20)

            val addResponse = sharedClient.post("$baseUrl/kollab/text/$documentId/comments") {
                contentType(ContentType.Application.Json)
                setBody(AddCommentRequest("Test Kommentar", "Max Mustermann", anchor))
            }
            assertThat(addResponse.status).isEqualTo(HttpStatusCode.NoContent)

            val comments = sharedClient.get("$baseUrl/kollab/text/$documentId/comments").body<CommentsResponse>()
            assertThat(comments.comments).hasSize(1)
            assertThat(comments.comments[0].content).isEqualTo("Test Kommentar")
            assertThat(comments.comments[0].author).isEqualTo("Max Mustermann")
            assertThat(comments.comments[0].anchor).isEqualTo(anchor)
            assertThat(comments.comments[0].dateTime.year).isGreaterThanOrEqualTo(2026)
        }
    }

    @Test
    fun `getComment returns single comment by id`() {
        runBlocking {
            val documentId = upload("test_three_comments.odt")
            val comments = sharedClient.get("$baseUrl/kollab/text/$documentId/comments").body<CommentsResponse>()

            val commentId = comments.comments[1].id
            val response = sharedClient.get("$baseUrl/kollab/text/$documentId/comments/$commentId")
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            assertThat(response.body<Comment>()).isEqualTo(comments.comments[1])
        }
    }

    @Test
    fun `getComment returns 404 when id does not exist`() {
        runBlocking {
            val documentId = upload("test_three_comments.odt")
            val response = sharedClient.get("$baseUrl/kollab/text/$documentId/comments/nonexistent")
            assertThat(response.status).isEqualTo(HttpStatusCode.NotFound)
        }
    }

    @Test
    fun `updateComment changes text and updates dateTime`() {
        runBlocking {
            val documentId = upload("test_with_comment.odt")
            val before = sharedClient.get("$baseUrl/kollab/text/$documentId/comments").body<CommentsResponse>()
            assertThat(before.comments).hasSize(1)
            val commentId = before.comments[0].id

            val updateResponse = sharedClient.patch("$baseUrl/kollab/text/$documentId/comments/$commentId") {
                contentType(ContentType.Application.Json)
                setBody(UpdateCommentRequest("Neuer Kommentartext"))
            }
            assertThat(updateResponse.status).isEqualTo(HttpStatusCode.OK)

            val after = sharedClient.get("$baseUrl/kollab/text/$documentId/comments").body<CommentsResponse>()
            assertThat(after.comments[0].content).isEqualTo("Neuer Kommentartext")
            assertThat(after.comments[0].author).isEqualTo(before.comments[0].author)
            assertThat(after.comments[0].dateTime).isGreaterThanOrEqualTo(before.comments[0].dateTime)
        }
    }

    @Test
    fun `updateComment returns 404 when id does not exist`() {
        runBlocking {
            val documentId = upload("test_with_comment.odt")
            val response = sharedClient.patch("$baseUrl/kollab/text/$documentId/comments/nonexistent") {
                contentType(ContentType.Application.Json)
                setBody(UpdateCommentRequest("Text"))
            }
            assertThat(response.status).isEqualTo(HttpStatusCode.NotFound)
        }
    }

    @Test
    fun `deleteComment removes comment from document`() {
        runBlocking {
            val documentId = upload("test_with_comment.odt")
            val before = sharedClient.get("$baseUrl/kollab/text/$documentId/comments").body<CommentsResponse>()
            assertThat(before.comments).hasSize(1)
            val commentId = before.comments[0].id

            val deleteResponse = sharedClient.delete("$baseUrl/kollab/text/$documentId/comments/$commentId")
            assertThat(deleteResponse.status).isEqualTo(HttpStatusCode.NoContent)

            val after = sharedClient.get("$baseUrl/kollab/text/$documentId/comments").body<CommentsResponse>()
            assertThat(after.comments).isEmpty()
        }
    }

    @Test
    fun `getComments returns all comments with anchor`() {
        runBlocking {
            val documentId = upload("test_three_comments.odt")
            val comments = sharedClient.get("$baseUrl/kollab/text/$documentId/comments").body<CommentsResponse>().comments

            assertThat(comments).hasSize(3)

            val first = LocalDateTime(2026, 6, 19, 12, 26, 21)
            val last  = LocalDateTime(2026, 6, 19, 12, 27,  8)

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
}
