package org.msc.liberekollab

import com.github.dockerjava.api.model.Bind
import com.github.dockerjava.api.model.Volume
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.msc.liberekollab.model.TextAnchor
import org.msc.liberekollab.storage.MinioStorage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Paths
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Testcontainers
class LibereKollabIT {

    companion object {
        val workspacePath: String = Files.createTempDirectory("liberekollab-test").toString()

        @Container
        @JvmStatic
        val libreoffice: GenericContainer<*> = GenericContainer(
            ImageFromDockerfile()
                .withDockerfile(Paths.get("docker/libreoffice/Dockerfile"))
        )
            .withExposedPorts(2002)
            .withCreateContainerCmdModifier { cmd ->
                val binds = cmd.hostConfig?.binds ?: emptyArray()
                cmd.hostConfig?.withBinds(*binds, Bind(workspacePath, Volume(workspacePath)))
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

    private fun storage() = MinioStorage(
        host = minio.host,
        port = minio.getMappedPort(9000),
        accessKey = "minioadmin",
        secretKey = "minioadmin",
        bucket = "test-documents"
    )

    private fun kollab(storage: MinioStorage) = LibereKollab(
        host = libreoffice.host,
        port = libreoffice.getMappedPort(2002),
        storage = storage,
        workspacePath = workspacePath
    )

    private fun upload(storage: MinioStorage, resourceName: String): String {
        val bytes = javaClass.getResourceAsStream("/$resourceName")!!.readBytes()
        return storage.upload(storage.nextId(), resourceName, ByteArrayInputStream(bytes))
    }

    @Test
    fun `getText extrahiert Text aus einer ODT Datei`() {
        val storage = storage()
        val documentId = upload(storage, "test_hallo.odt")
        val text = runBlocking { kollab(storage).getText(documentId) }
        assertEquals("Hallo test", text.trim())
    }

    @Test
    fun `getPageCount gibt korrekte Seitenanzahl zurück`() {
        val storage = storage()
        val documentId = upload(storage, "test_three_pages.odt")
        val pageCount = runBlocking { kollab(storage).getPageCount(documentId) }
        assertEquals(3, pageCount)
    }

    @Test
    fun `getChapters gibt alle Kapitelüberschriften zurück`() {
        val storage = storage()
        val documentId = upload(storage, "test_two_chapters.odt")
        val chapters = runBlocking { kollab(storage).getChapters(documentId) }
        assertEquals(listOf("Kapitel 1 Test Überschrift Hallo", "Kapitel 2 Test Überschrift Bla"), chapters)
    }

    @Test
    fun `getTextByPages gibt Text der angegebenen Seite zurück`() {
        val storage = storage()
        val documentId = upload(storage, "test_three_pages.odt")
        val text = runBlocking { kollab(storage).getTextByPages(documentId, 2, 2) }
        assertEquals("Text Seite 2", text.trim())
    }

    @Test
    fun `getTextByChapter gibt Text des angegebenen Kapitels zurück`() {
        val storage = storage()
        val documentId = upload(storage, "test_two_chapters.odt")
        val text = runBlocking { kollab(storage).getTextByChapter(documentId, "Kapitel 1 Test Überschrift Hallo") }
        assertEquals("Das ist der Text für Kapitel 1", text.trim())
    }

    @Test
    fun `addComment fügt einen Kommentar am TextAnker ein`() {
        val storage = storage()
        val documentId = upload(storage, "test_add_comment.odt")
        val anchor = TextAnchor("add a comment", 0, 7, 20)

        runBlocking { kollab(storage).addComment(documentId, "Test Kommentar", "Max Mustermann", anchor) }

        val comments = runBlocking { kollab(storage).getComments(documentId) }
        assertEquals(1, comments.size)
        assertEquals("Test Kommentar", comments[0].content)
        assertEquals("Max Mustermann", comments[0].author)
        assertEquals(anchor, comments[0].anchor)
        assertTrue(comments[0].dateTime.year >= 2026)
    }

    @Test
    fun `getComments gibt alle Kommentare mit Anker zurück`() {
        val storage = storage()
        val documentId = upload(storage, "test_three_comments.odt")
        val comments = runBlocking { kollab(storage).getComments(documentId) }

        assertEquals(3, comments.size)

        assertEquals("Unknown Author", comments[0].author)
        assertEquals("Text1 wird groß geschrieben", comments[0].content)
        assertEquals(LocalDateTime(2026, 6, 19, 12, 26, 21), comments[0].dateTime)
        assertEquals(TextAnchor("text1", 2, 8, 13), comments[0].anchor)

        assertEquals("Unknown Author", comments[1].author)
        assertEquals("Verstehe ich nicht", comments[1].content)
        assertEquals(LocalDateTime(2026, 6, 19, 12, 26, 51), comments[1].dateTime)
        assertEquals(TextAnchor("text2", 6, 8, 13), comments[1].anchor)

        assertEquals("Unknown Author", comments[2].author)
        assertEquals("Das ist korrekt", comments[2].content)
        assertEquals(LocalDateTime(2026, 6, 19, 12, 27, 8), comments[2].dateTime)
        assertEquals(TextAnchor("Text3", 10, 8, 13), comments[2].anchor)
    }
}
