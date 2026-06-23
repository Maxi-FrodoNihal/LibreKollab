package org.msc.liberekollab

import com.github.dockerjava.api.model.Bind
import com.github.dockerjava.api.model.Volume
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.msc.liberekollab.model.change.ChangeAction
import org.msc.liberekollab.model.change.ChangeStatus
import org.msc.liberekollab.model.TextAnchor
import org.msc.liberekollab.model.text.MarkIndex
import org.msc.liberekollab.model.text.MarkedText
import org.msc.liberekollab.model.text.properties.BoldProperty
import org.msc.liberekollab.model.text.properties.ItalicProperty
import org.msc.liberekollab.model.text.properties.StrikethroughProperty
import org.msc.liberekollab.model.text.properties.UnderlineProperty
import org.msc.liberekollab.logging.LoggingKollabAPI
import org.msc.liberekollab.adapter.MinioAdapter
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Paths
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.Test

@Testcontainers
class LibereKollabIT {

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
    }

    private fun storage() = MinioAdapter(
        host = minio.host,
        port = minio.getMappedPort(9000),
        accessKey = "minioadmin",
        secretKey = "minioadmin",
        bucket = "test-documents"
    )

    private fun kollab(storage: MinioAdapter) = LoggingKollabAPI(LibereKollab(
        host = libreoffice.host,
        port = libreoffice.getMappedPort(2002),
        storage = storage,
        workspacePath = workspacePath,
        containerWorkspacePath = CONTAINER_WORKSPACE_PATH
    ))

    private fun upload(storage: MinioAdapter, resourceName: String): String {
        val bytes = javaClass.getResourceAsStream("/$resourceName")!!.readBytes()
        return runBlocking { storage.upload(storage.nextId(), resourceName, ByteArrayInputStream(bytes)) }
    }

    @Test
    fun `editText replaces text range in document`() {
        val storage = storage()
        val documentId = upload(storage, "test_hallo.odt")
        val anchor = TextAnchor("Hallo", 0, 0, 5)

        runBlocking { kollab(storage).editText(documentId, anchor, MarkedText("Tschüss")) }

        assertThat(runBlocking { kollab(storage).getText(documentId, ChangeStatus.AFTER) }.text.trim())
            .isEqualTo("Tschüss test")
        assertThat(runBlocking { kollab(storage).getText(documentId, ChangeStatus.BEFORE) }.text.trim())
            .isEqualTo("Hallo test")
    }

    @Test
    fun `getChanges returns tracked changes`() {
        val storage = storage()
        val documentId = upload(storage, "test_hallo.odt")

        runBlocking { kollab(storage).editText(documentId, TextAnchor("Hallo", 0, 0, 5), MarkedText("Tschüss")) }

        val changes = runBlocking { kollab(storage).getChanges(documentId) }
        assertThat(changes).hasSize(2)
        assertThat(changes.map { it.action }).containsExactlyInAnyOrder(ChangeAction.DELETE, ChangeAction.INSERT)
        assertThat(changes.first { it.action == ChangeAction.DELETE }.text).isEqualTo("Hallo")
        assertThat(changes.first { it.action == ChangeAction.INSERT }.text).isEqualTo("Tschüss")
    }

    @Test
    fun `editText throws IllegalArgumentException for invalid anchor`() {
        val storage = storage()
        val documentId = upload(storage, "test_hallo.odt")
        val anchor = TextAnchor("Hallo", 99, 0, 5)

        assertThatThrownBy { runBlocking { kollab(storage).editText(documentId, anchor, MarkedText("Tschüss")) } }
            .hasCauseInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `getText extracts text from odt file`() {
        val storage = storage()
        val documentId = upload(storage, "test_hallo.odt")
        val markedText = runBlocking { kollab(storage).getText(documentId) }
        assertThat(markedText.text).isEqualTo("Hallo test")
    }

    @Test
    fun `getText extracts text formatting properties`() {
        val storage = storage()
        val documentId = upload(storage, "test_text_properties.odt")
        val markedText = runBlocking { kollab(storage).getText(documentId) }

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

    @Test
    fun `editText inserts text with formatting property`() {
        val storage = storage()
        val documentId = upload(storage, "test_hallo.odt")
        val anchor = TextAnchor("Hallo", 0, 0, 5)
        val boldText = MarkedText("Fett", listOf(BoldProperty(MarkIndex(0, 0, 4))))

        runBlocking { kollab(storage).editText(documentId, anchor, boldText) }

        val result = runBlocking { kollab(storage).getText(documentId, ChangeStatus.AFTER) }
        assertThat(result.properties).hasSize(1)
        assertThat(result.properties[0]).isInstanceOf(BoldProperty::class.java)
        assertThat(result.properties[0].markIndex).isEqualTo(MarkIndex(0, 0, 4))
    }

    @Test
    fun `editText removes formatting when replacing with plain text`() {
        val storage = storage()
        val documentId = upload(storage, "test_text_properties.odt")
        val anchor = TextAnchor("Das ist ein fetter Text", 0, 0, 23)

        runBlocking { kollab(storage).editText(documentId, anchor, MarkedText("Das ist ein fetter Text")) }

        val result = runBlocking { kollab(storage).getText(documentId, ChangeStatus.AFTER) }
        assertThat(result.properties.filterIsInstance<BoldProperty>()).isEmpty()
    }

    @Test
    fun `getPageCount returns correct page count`() {
        val storage = storage()
        val documentId = upload(storage, "test_three_pages.odt")
        val pageCount = runBlocking { kollab(storage).getPageCount(documentId) }
        assertThat(pageCount).isEqualTo(3)
    }

    @Test
    fun `getChapters returns all chapter headings`() {
        val storage = storage()
        val documentId = upload(storage, "test_two_chapters.odt")
        val chapters = runBlocking { kollab(storage).getChapters(documentId) }
        assertThat(chapters).containsExactly(
            "Kapitel 1 Test Überschrift Hallo",
            "Kapitel 2 Test Überschrift Bla"
        )
    }

    @Test
    fun `getTextByPages returns text of given page`() {
        val storage = storage()
        val documentId = upload(storage, "test_three_pages.odt")
        val markedText = runBlocking { kollab(storage).getTextByPages(documentId, 2, 2) }
        assertThat(markedText.text.trim()).isEqualTo("Text Seite 2")
    }

    @Test
    fun `getTextByChapter returns text of given chapter`() {
        val storage = storage()
        val documentId = upload(storage, "test_two_chapters.odt")
        val markedText = runBlocking { kollab(storage).getTextByChapter(documentId, "Kapitel 1 Test Überschrift Hallo") }
        assertThat(markedText.text.trim()).isEqualTo("Das ist der Text für Kapitel 1")
    }

    @Test
    fun `addComment inserts comment at text anchor`() {
        val storage = storage()
        val documentId = upload(storage, "test_add_comment.odt")
        val anchor = TextAnchor("add a comment", 0, 7, 20)

        runBlocking { kollab(storage).addComment(documentId, "Test Kommentar", "Max Mustermann", anchor) }

        val comments = runBlocking { kollab(storage).getComments(documentId) }
        assertThat(comments).hasSize(1)
        assertThat(comments[0].content).isEqualTo("Test Kommentar")
        assertThat(comments[0].author).isEqualTo("Max Mustermann")
        assertThat(comments[0].anchor).isEqualTo(anchor)
        assertThat(comments[0].dateTime.year).isGreaterThanOrEqualTo(2026)
    }

    @Test
    fun `getComment returns single comment by id`() {
        val storage = storage()
        val documentId = upload(storage, "test_three_comments.odt")
        val comments = runBlocking { kollab(storage).getComments(documentId) }

        val found = runBlocking { kollab(storage).getComment(documentId, comments[1].id) }
        assertThat(found).isEqualTo(comments[1])
    }

    @Test
    fun `getComment returns null when id does not exist`() {
        val storage = storage()
        val documentId = upload(storage, "test_three_comments.odt")

        val found = runBlocking { kollab(storage).getComment(documentId, "nonexistent") }
        assertThat(found).isNull()
    }

    @Test
    fun `updateComment changes text and updates dateTime`() {
        val storage = storage()
        val documentId = upload(storage, "test_with_comment.odt")
        val before = runBlocking { kollab(storage).getComments(documentId) }
        assertThat(before).hasSize(1)

        runBlocking { kollab(storage).updateComment(documentId, before[0].id, "Neuer Kommentartext") }

        val after = runBlocking { kollab(storage).getComments(documentId) }
        assertThat(after[0].content).isEqualTo("Neuer Kommentartext")
        assertThat(after[0].author).isEqualTo(before[0].author)
        assertThat(after[0].dateTime).isGreaterThanOrEqualTo(before[0].dateTime)
    }

    @Test
    fun `updateComment throws NoSuchElementException when id does not exist`() {
        val storage = storage()
        val documentId = upload(storage, "test_with_comment.odt")

        assertThatThrownBy { runBlocking { kollab(storage).updateComment(documentId, "nonexistent", "Text") } }
            .hasCauseInstanceOf(NoSuchElementException::class.java)
    }

    @Test
    fun `deleteComment removes comment from document`() {
        val storage = storage()
        val documentId = upload(storage, "test_with_comment.odt")
        val before = runBlocking { kollab(storage).getComments(documentId) }
        assertThat(before).hasSize(1)
        val commentId = before[0].id

        runBlocking { kollab(storage).deleteComment(documentId, commentId) }

        val after = runBlocking { kollab(storage).getComments(documentId) }
        assertThat(after).isEmpty()
    }

    @Test
    fun `getComments returns all comments with anchor`() {
        val storage = storage()
        val documentId = upload(storage, "test_three_comments.odt")
        val comments = runBlocking { kollab(storage).getComments(documentId) }

        assertThat(comments).hasSize(3)

        assertThat(comments[0].author).isEqualTo("Unknown Author")
        assertThat(comments[0].content).isEqualTo("Text1 wird groß geschrieben")
        assertThat(comments[0].dateTime).isEqualTo(LocalDateTime(2026, 6, 19, 12, 26, 21))
        assertThat(comments[0].anchor).isEqualTo(TextAnchor("text1", 2, 8, 13))

        assertThat(comments[1].author).isEqualTo("Unknown Author")
        assertThat(comments[1].content).isEqualTo("Verstehe ich nicht")
        assertThat(comments[1].dateTime).isEqualTo(LocalDateTime(2026, 6, 19, 12, 26, 51))
        assertThat(comments[1].anchor).isEqualTo(TextAnchor("text2", 6, 8, 13))

        assertThat(comments[2].author).isEqualTo("Unknown Author")
        assertThat(comments[2].content).isEqualTo("Das ist korrekt")
        assertThat(comments[2].dateTime).isEqualTo(LocalDateTime(2026, 6, 19, 12, 27, 8))
        assertThat(comments[2].anchor).isEqualTo(TextAnchor("Text3", 10, 8, 13))
    }
}
