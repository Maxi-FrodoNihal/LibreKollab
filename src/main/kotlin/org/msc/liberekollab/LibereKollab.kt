package org.msc.liberekollab

import com.sun.star.beans.PropertyValue
import com.sun.star.beans.XPropertySet
import com.sun.star.bridge.XUnoUrlResolver
import com.sun.star.comp.helper.Bootstrap
import com.sun.star.container.XEnumerationAccess
import com.sun.star.frame.XComponentLoader
import com.sun.star.frame.XModel
import com.sun.star.lang.XComponent
import com.sun.star.lang.XMultiComponentFactory
import com.sun.star.lang.XMultiServiceFactory
import com.sun.star.frame.XStorable
import com.sun.star.lang.XServiceInfo
import com.sun.star.text.XPageCursor
import com.sun.star.text.XTextContent
import com.sun.star.text.XTextDocument
import com.sun.star.text.XTextFieldsSupplier
import com.sun.star.text.XTextRange
import com.sun.star.text.XTextViewCursorSupplier
import com.sun.star.uno.UnoRuntime
import com.sun.star.uno.XComponentContext
import com.sun.star.util.DateTime
import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import org.msc.liberekollab.abstrakt.IOAPI
import org.msc.liberekollab.abstrakt.KollabAPI
import org.msc.liberekollab.model.Comment
import org.msc.liberekollab.model.TextAnchor
import org.msc.liberekollab.storage.MinioStorage
import java.io.ByteArrayOutputStream
import java.io.File

private fun XPropertySet.outlineLevel(): Int =
    when (val v = getPropertyValue("OutlineLevel")) {
        is Int -> v
        is Short -> v.toInt()
        else -> 0
    }

class LibereKollab(
    private val host: String,
    private val port: Int,
    private val storage: IOAPI,
    private val workspacePath: String
) : KollabAPI {

    constructor() : this(
        host = dotenv { ignoreIfMissing = true }["LIBREOFFICE_HOST"],
        port = dotenv { ignoreIfMissing = true }["LIBREOFFICE_PORT"].toInt(),
        storage = MinioStorage(),
        workspacePath = dotenv { ignoreIfMissing = true }["WORKSPACE_PATH"]
    )

    private val libreOfficeDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val remoteContext: XComponentContext by lazy { connect() }

    private fun connect(): XComponentContext {
        val localContext = Bootstrap.createInitialComponentContext(null)
        val urlResolver = UnoRuntime.queryInterface(
            XUnoUrlResolver::class.java,
            localContext.serviceManager.createInstanceWithContext(
                "com.sun.star.bridge.UnoUrlResolver", localContext
            )
        )
        return UnoRuntime.queryInterface(
            XComponentContext::class.java,
            urlResolver.resolve("uno:socket,host=$host,port=$port;urp;StarOffice.ComponentContext")
        )
    }

    private fun getDesktop(): XComponentLoader {
        val serviceManager = UnoRuntime.queryInterface(
            XMultiComponentFactory::class.java,
            remoteContext.serviceManager
        )
        return UnoRuntime.queryInterface(
            XComponentLoader::class.java,
            serviceManager.createInstanceWithContext("com.sun.star.frame.Desktop", remoteContext)
        )
    }

    private fun <T> withWorkspaceFile(documentId: String, block: (File) -> T): T {
        val (tempFile, _) = resolveWorkspaceFile(documentId)
        return try {
            block(tempFile)
        } finally {
            tempFile.delete()
        }
    }

    private fun <T> withWorkspaceFileAndWriteback(documentId: String, block: (File) -> T): T {
        val (tempFile, fileName) = resolveWorkspaceFile(documentId)
        return try {
            block(tempFile)
        } finally {
            storage.upload(documentId, fileName, tempFile.inputStream())
            tempFile.delete()
        }
    }

    private fun resolveWorkspaceFile(documentId: String): Pair<File, String> {
        val objectName = storage.ls().first { it.startsWith(documentId) }
        val fileName = objectName.removePrefix("${documentId}_")
        val bytes = (storage.download(documentId) as ByteArrayOutputStream).toByteArray()
        val tempFile = File("$workspacePath/$objectName")
        tempFile.parentFile?.mkdirs()
        tempFile.writeBytes(bytes)
        return Pair(tempFile, fileName)
    }

    private fun <T> withDocumentMutating(documentId: String, block: (XTextDocument) -> T): T =
        withWorkspaceFileAndWriteback(documentId) { tempFile ->
            val loadProps = arrayOf(PropertyValue().apply { Name = "Hidden"; Value = true })
            val component = getDesktop().loadComponentFromURL(
                "file://${tempFile.absolutePath}", "_blank", 0, loadProps
            )
            val textDoc = UnoRuntime.queryInterface(XTextDocument::class.java, component)
            try {
                val result = block(textDoc)
                UnoRuntime.queryInterface(XStorable::class.java, component).store()
                result
            } finally {
                UnoRuntime.queryInterface(XComponent::class.java, component)?.dispose()
            }
        }

    private fun <T> withDocument(documentId: String, block: (XTextDocument) -> T): T {
        return withWorkspaceFile(documentId) { tempFile ->
            val loadProps = arrayOf(PropertyValue().apply {
                Name = "Hidden"
                Value = true
            })
            val component = getDesktop().loadComponentFromURL(
                "file://${tempFile.absolutePath}", "_blank", 0, loadProps
            )
            val textDoc = UnoRuntime.queryInterface(XTextDocument::class.java, component)
            try {
                block(textDoc)
            } finally {
                UnoRuntime.queryInterface(XComponent::class.java, component)?.dispose()
            }
        }
    }

    private fun pageCursorOf(textDoc: XTextDocument): XPageCursor {
        val model = UnoRuntime.queryInterface(XModel::class.java, textDoc)
        val vcSupplier = UnoRuntime.queryInterface(
            XTextViewCursorSupplier::class.java,
            model.currentController
        )
        return UnoRuntime.queryInterface(XPageCursor::class.java, vcSupplier.viewCursor)
    }

    override suspend fun getText(documentId: String): String = withContext(libreOfficeDispatcher) {
        withDocument(documentId) { textDoc ->
            textDoc.text.string
        }
    }

    override suspend fun getPageCount(documentId: String): Int = withContext(libreOfficeDispatcher) {
        withDocument(documentId) { textDoc ->
            val model = UnoRuntime.queryInterface(XModel::class.java, textDoc)
            val vc = UnoRuntime.queryInterface(XTextViewCursorSupplier::class.java, model.currentController).viewCursor
            vc.gotoEnd(false)
            UnoRuntime.queryInterface(XPageCursor::class.java, vc).page.toInt()
        }
    }

    override suspend fun getChapters(documentId: String): List<String> = withContext(libreOfficeDispatcher) {
        withDocument(documentId) { textDoc ->
            val chapters = mutableListOf<String>()
            val paragraphs = UnoRuntime.queryInterface(XEnumerationAccess::class.java, textDoc.text)
                .createEnumeration()
            while (paragraphs.hasMoreElements()) {
                val element = paragraphs.nextElement()
                val propSet = UnoRuntime.queryInterface(XPropertySet::class.java, element)
                val outlineLevel = propSet.outlineLevel()
                if (outlineLevel > 0) {
                    chapters.add(UnoRuntime.queryInterface(XTextRange::class.java, element).string)
                }
            }
            chapters
        }
    }

    override suspend fun getTextByPages(documentId: String, fromPage: Int, toPage: Int): String =
        withContext(libreOfficeDispatcher) {
            withDocument(documentId) { textDoc ->
                val pc = pageCursorOf(textDoc)
                val vc = UnoRuntime.queryInterface(
                    XTextViewCursorSupplier::class.java,
                    UnoRuntime.queryInterface(XModel::class.java, textDoc).currentController
                ).viewCursor

                val sb = StringBuilder()
                val paragraphs = UnoRuntime.queryInterface(XEnumerationAccess::class.java, textDoc.text)
                    .createEnumeration()

                while (paragraphs.hasMoreElements()) {
                    val element = paragraphs.nextElement()
                    val textRange = UnoRuntime.queryInterface(XTextRange::class.java, element)
                    vc.gotoRange(textRange.start, false)
                    val pageNum = pc.page.toInt()
                    if (pageNum in fromPage..toPage) sb.append(textRange.string).append("\n")
                    if (pageNum > toPage) break
                }
                sb.toString().trimEnd()
            }
        }

    private suspend fun <T> withEnsuredEditMode(documentId: String, block: suspend () -> T): T {
        if (!getEditMode(documentId)) setEditMode(documentId, true)
        return block()
    }

    override suspend fun setEditMode(documentId: String, editMode: Boolean): Unit = withContext(libreOfficeDispatcher) {
        withDocumentMutating(documentId) { textDoc ->
            UnoRuntime.queryInterface(XPropertySet::class.java, textDoc)
                .setPropertyValue("RecordChanges", editMode)
        }
    }

    override suspend fun getEditMode(documentId: String): Boolean = withContext(libreOfficeDispatcher) {
        withDocument(documentId) { textDoc ->
            UnoRuntime.queryInterface(XPropertySet::class.java, textDoc)
                .getPropertyValue("RecordChanges") as Boolean
        }
    }

    override suspend fun getComments(documentId: String): List<Comment> = withContext(libreOfficeDispatcher) {
        withDocument(documentId) { textDoc ->
            val fieldSupplier = UnoRuntime.queryInterface(XTextFieldsSupplier::class.java, textDoc)
            val fieldEnum = fieldSupplier.textFields.createEnumeration()

            val comments = mutableListOf<Comment>()
            while (fieldEnum.hasMoreElements()) {
                val field = fieldEnum.nextElement()
                val serviceInfo = UnoRuntime.queryInterface(XServiceInfo::class.java, field)
                if (!serviceInfo.supportsService("com.sun.star.text.TextField.Annotation")) continue

                val propSet = UnoRuntime.queryInterface(XPropertySet::class.java, field)
                val anchorRange = UnoRuntime.queryInterface(XTextContent::class.java, field).anchor

                val anchor = buildTextAnchor(textDoc, anchorRange)
                val author = propSet.getPropertyValue("Author") as String
                val content = propSet.getPropertyValue("Content") as String
                val dt = propSet.getPropertyValue("DateTimeValue") as DateTime
                val dateTime = LocalDateTime(
                    dt.Year.toInt(), dt.Month.toInt(), dt.Day.toInt(),
                    dt.Hours.toInt(), dt.Minutes.toInt(), dt.Seconds.toInt()
                )

                comments.add(Comment.of(anchor, author, content, dateTime))
            }
            comments.sortedWith(compareBy({ it.anchor.paragraphIndex }, { it.anchor.charStart }))
        }
    }

    override suspend fun addComment(documentId: String, commentText: String, author: String, anchor: TextAnchor) {
        withEnsuredEditMode(documentId) {
            withContext(libreOfficeDispatcher) {
                withDocumentMutating(documentId) { textDoc ->
                    val anchorRange = resolveAnchorRange(textDoc, anchor)
                    val serviceFactory = UnoRuntime.queryInterface(XMultiServiceFactory::class.java, textDoc)
                    val annotationField = serviceFactory.createInstance("com.sun.star.text.TextField.Annotation")
                    val now = java.time.LocalDateTime.now()
                    UnoRuntime.queryInterface(XPropertySet::class.java, annotationField).apply {
                        setPropertyValue("Content", commentText)
                        setPropertyValue("Author", author)
                        setPropertyValue("DateTimeValue", DateTime().apply {
                            Year = now.year.toShort()
                            Month = now.monthValue.toShort()
                            Day = now.dayOfMonth.toShort()
                            Hours = now.hour.toShort()
                            Minutes = now.minute.toShort()
                            Seconds = now.second.toShort()
                        })
                    }
                    textDoc.text.insertTextContent(
                        anchorRange,
                        UnoRuntime.queryInterface(XTextContent::class.java, annotationField),
                        true
                    )
                }
            }
        }
    }

    private fun resolveAnchorRange(textDoc: XTextDocument, anchor: TextAnchor): XTextRange {
        val paragraphs = UnoRuntime.queryInterface(XEnumerationAccess::class.java, textDoc.text)
            .createEnumeration()
        var idx = 0
        while (paragraphs.hasMoreElements()) {
            val para = paragraphs.nextElement()
            if (idx == anchor.paragraphIndex) {
                val paraStart = UnoRuntime.queryInterface(XTextRange::class.java, para).start
                val cursor = textDoc.text.createTextCursorByRange(paraStart)
                cursor.goRight(anchor.charStart.toShort(), false)
                cursor.goRight((anchor.charEnd - anchor.charStart).toShort(), true)
                return cursor
            }
            idx++
        }
        throw IllegalArgumentException("Paragraph index ${anchor.paragraphIndex} not found in document")
    }

    private fun buildTextAnchor(textDoc: XTextDocument, anchorRange: XTextRange): TextAnchor {
        val cursor = textDoc.text.createTextCursorByRange(anchorRange.start)
        cursor.gotoStart(true)
        val textBefore = cursor.string

        val paragraphIndex = textBefore.count { it == '\n' }
        val charStart = textBefore.substringAfterLast('\n', textBefore).length
        val charEnd = charStart + anchorRange.string.length

        return TextAnchor(anchorRange.string, paragraphIndex, charStart, charEnd)
    }

    override suspend fun getTextByChapter(documentId: String, chapter: String): String =
        withContext(libreOfficeDispatcher) {
            withDocument(documentId) { textDoc ->
                val sb = StringBuilder()
                var inChapter = false
                var chapterLevel = 0

                val paragraphs = UnoRuntime.queryInterface(XEnumerationAccess::class.java, textDoc.text)
                    .createEnumeration()

                while (paragraphs.hasMoreElements()) {
                    val element = paragraphs.nextElement()
                    val propSet = UnoRuntime.queryInterface(XPropertySet::class.java, element)
                    val textRange = UnoRuntime.queryInterface(XTextRange::class.java, element)
                    val outlineLevel = propSet.outlineLevel()
                    val paraText = textRange.string

                    if (outlineLevel > 0) {
                        if (inChapter && outlineLevel <= chapterLevel) break
                        if (paraText.trim() == chapter.trim()) {
                            inChapter = true
                            chapterLevel = outlineLevel
                        }
                    } else if (inChapter && paraText.isNotBlank()) {
                        sb.append(paraText).append("\n")
                    }
                }
                sb.toString().trimEnd()
            }
        }
}
