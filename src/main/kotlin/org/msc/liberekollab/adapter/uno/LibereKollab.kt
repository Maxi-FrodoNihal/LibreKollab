package org.msc.liberekollab.adapter.uno

import com.sun.star.awt.FontSlant
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
import org.msc.liberekollab.domain.port.IOAPI
import org.msc.liberekollab.domain.port.KollabAPI
import org.msc.liberekollab.domain.model.change.Change
import org.msc.liberekollab.domain.model.change.ChangeAction
import org.msc.liberekollab.domain.model.change.ChangeStatus
import org.msc.liberekollab.domain.model.Comment
import org.msc.liberekollab.domain.model.TextAnchor
import org.msc.liberekollab.domain.model.text.MarkedText
import org.msc.liberekollab.domain.model.text.MarkIndex
import org.msc.liberekollab.domain.model.text.properties.BoldProperty
import org.msc.liberekollab.domain.model.text.properties.ItalicProperty
import org.msc.liberekollab.domain.model.text.properties.StrikethroughProperty
import org.msc.liberekollab.domain.model.text.properties.TextProperty
import org.msc.liberekollab.domain.model.text.properties.UnderlineProperty
import org.msc.liberekollab.adapter.minio.MinioAdapter
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
    private val workspacePath: String,
    private val containerWorkspacePath: String = workspacePath
) : KollabAPI {

    constructor() : this(
        host = dotenv { ignoreIfMissing = true }["LIBREOFFICE_HOST"],
        port = dotenv { ignoreIfMissing = true }["LIBREOFFICE_PORT"].toInt(),
        storage = MinioAdapter(),
        workspacePath = dotenv { ignoreIfMissing = true }.get(
            "WORKSPACE_PATH"
        ) ?: (System.getProperty("user.dir") + "/workspace"),
        containerWorkspacePath = "/workspace"
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

    private suspend fun <T> withWorkspaceFile(documentId: String, block: (File) -> T): T {
        val (tempFile, _) = resolveWorkspaceFile(documentId)
        return try {
            block(tempFile)
        } finally {
            tempFile.delete()
        }
    }

    private suspend fun <T> withWorkspaceFileAndWriteback(documentId: String, block: (File) -> T): T {
        val (tempFile, fileName) = resolveWorkspaceFile(documentId)
        return try {
            block(tempFile)
        } finally {
            storage.upload(documentId, fileName, tempFile.inputStream())
            tempFile.delete()
        }
    }

    private suspend fun resolveWorkspaceFile(documentId: String): Pair<File, String> {
        val objectName = storage.ls().first { it.startsWith(documentId) }
        val fileName = objectName.removePrefix("${documentId}_")
        val bytes = (storage.download(documentId) as ByteArrayOutputStream).toByteArray()
        val tempFile = File("$workspacePath/$objectName")
        tempFile.parentFile?.mkdirs()
        tempFile.writeBytes(bytes)
        return Pair(tempFile, fileName)
    }

    private suspend fun <T> withDocumentMutating(documentId: String, block: (XTextDocument) -> T): T =
        withWorkspaceFileAndWriteback(documentId) { tempFile ->
            val loadProps = arrayOf(PropertyValue().apply { Name = "Hidden"; Value = true })
            val component = getDesktop().loadComponentFromURL(
                "file://$containerWorkspacePath/${tempFile.name}", "_blank", 0, loadProps
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

    private suspend fun <T> withDocument(documentId: String, block: (XTextDocument) -> T): T {
        return withWorkspaceFile(documentId) { tempFile ->
            val loadProps = arrayOf(PropertyValue().apply {
                Name = "Hidden"
                Value = true
            })
            val component = getDesktop().loadComponentFromURL(
                "file://$containerWorkspacePath/${tempFile.name}", "_blank", 0, loadProps
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

    private fun extractParagraphMarkedText(para: Any, changeStatus: ChangeStatus, paragraphIndex: Int): Pair<String, List<TextProperty>> {
        val sb = StringBuilder()
        val properties = mutableListOf<TextProperty>()
        var offset = 0
        var inDelete = false
        var inInsert = false
        val portions = UnoRuntime.queryInterface(XEnumerationAccess::class.java, para)
            ?.createEnumeration() ?: return Pair("", emptyList())
        while (portions.hasMoreElements()) {
            val portion = portions.nextElement()
            val propSet = UnoRuntime.queryInterface(XPropertySet::class.java, portion) ?: continue
            when (propSet.getPropertyValue("TextPortionType") as? String) {
                "Redline" -> {
                    val isStart = propSet.getPropertyValue("IsStart") as? Boolean ?: false
                    when (propSet.getPropertyValue("RedlineType") as? String) {
                        "Delete" -> inDelete = isStart
                        "Insert" -> inInsert = isStart
                    }
                }
                "Text" -> {
                    val include = when (changeStatus) {
                        ChangeStatus.BEFORE -> !inInsert
                        ChangeStatus.AFTER  -> !inDelete
                        ChangeStatus.FUSION -> true
                    }
                    if (include) {
                        val text = UnoRuntime.queryInterface(XTextRange::class.java, portion)?.string ?: ""
                        if (text.isNotEmpty()) {
                            val idx = MarkIndex(paragraphIndex, offset, offset + text.length)
                            collectFormatting(propSet, idx, properties)
                            sb.append(text)
                            offset += text.length
                        }
                    }
                }
            }
        }
        return Pair(sb.toString(), properties)
    }

    private fun collectFormatting(propSet: XPropertySet, idx: MarkIndex, properties: MutableList<TextProperty>) {
        val charWeight = try { propSet.getPropertyValue("CharWeight") as? Float } catch (e: Exception) { null }
        if (charWeight != null && charWeight >= 150f) properties.add(BoldProperty(idx))

        val charPosture = try { propSet.getPropertyValue("CharPosture") as? FontSlant } catch (e: Exception) { null }
        if (charPosture != null && charPosture != FontSlant.NONE && charPosture != FontSlant.DONTKNOW) properties.add(ItalicProperty(idx))

        val charUnderline = try { (propSet.getPropertyValue("CharUnderline") as? Number)?.toInt() } catch (e: Exception) { null }
        if (charUnderline != null && charUnderline != 0) properties.add(UnderlineProperty(idx))

        val charStrikeout = try { (propSet.getPropertyValue("CharStrikeout") as? Number)?.toInt() } catch (e: Exception) { null }
        if (charStrikeout != null && charStrikeout != 0) properties.add(StrikethroughProperty(idx))
    }

    override suspend fun getText(documentId: String, changeStatus: ChangeStatus): MarkedText =
        withContext(libreOfficeDispatcher) {
            withDocument(documentId) { textDoc ->
                val sb = StringBuilder()
                val properties = mutableListOf<TextProperty>()
                val paragraphs = UnoRuntime.queryInterface(XEnumerationAccess::class.java, textDoc.text)
                    .createEnumeration()
                var paraIdx = 0
                while (paragraphs.hasMoreElements()) {
                    if (paraIdx > 0) sb.append("\n")
                    val (text, props) = extractParagraphMarkedText(paragraphs.nextElement(), changeStatus, paraIdx)
                    sb.append(text)
                    properties.addAll(props)
                    paraIdx++
                }
                MarkedText(sb.toString(), properties)
            }
        }

    override suspend fun getChanges(documentId: String): List<Change> =
        withContext(libreOfficeDispatcher) {
            withDocument(documentId) { textDoc ->
                val changes = mutableListOf<Change>()
                val paragraphs = UnoRuntime.queryInterface(XEnumerationAccess::class.java, textDoc.text)
                    .createEnumeration()
                var paragraphIdx = 0
                while (paragraphs.hasMoreElements()) {
                    val para = paragraphs.nextElement()
                    val portions = UnoRuntime.queryInterface(XEnumerationAccess::class.java, para)
                        ?.createEnumeration() ?: continue
                    var charOffset = 0
                    var redlineAction: ChangeAction? = null
                    var redlineAuthor = ""
                    var redlineDateTime: LocalDateTime? = null
                    var redlineText = StringBuilder()
                    var redlineStart = 0
                    while (portions.hasMoreElements()) {
                        val portion = portions.nextElement()
                        val propSet = UnoRuntime.queryInterface(XPropertySet::class.java, portion)
                        when (propSet.getPropertyValue("TextPortionType") as? String) {
                            "Redline" -> {
                                val isStart = propSet.getPropertyValue("IsStart") as? Boolean ?: false
                                if (isStart) {
                                    redlineAction = when (propSet.getPropertyValue("RedlineType") as? String) {
                                        "Insert" -> ChangeAction.INSERT
                                        "Delete" -> ChangeAction.DELETE
                                        else -> null
                                    }
                                    redlineAuthor = propSet.getPropertyValue("RedlineAuthor") as? String ?: ""
                                    val dt = propSet.getPropertyValue("RedlineDateTime") as? DateTime
                                    redlineDateTime = dt?.let {
                                        LocalDateTime(it.Year.toInt(), it.Month.toInt(), it.Day.toInt(),
                                            it.Hours.toInt(), it.Minutes.toInt(), it.Seconds.toInt())
                                    }
                                    redlineText = StringBuilder()
                                    redlineStart = charOffset
                                } else {
                                    val text = redlineText.toString()
                                    if (redlineAction != null && redlineDateTime != null && text.isNotEmpty()) {
                                        changes.add(Change(
                                            action = redlineAction!!,
                                            author = redlineAuthor,
                                            dateTime = redlineDateTime!!,
                                            text = text,
                                            anchor = TextAnchor(text, paragraphIdx, redlineStart, redlineStart + text.length)
                                        ))
                                    }
                                    redlineAction = null
                                }
                            }
                            "Text" -> {
                                val text = UnoRuntime.queryInterface(XTextRange::class.java, portion)?.string ?: ""
                                if (redlineAction != null) redlineText.append(text)
                                charOffset += text.length
                            }
                        }
                    }
                    paragraphIdx++
                }
                changes
            }
        }

    override suspend fun editText(documentId: String, anchor: TextAnchor, newText: MarkedText) {
        withEnsuredEditMode(documentId) {
            withContext(libreOfficeDispatcher) {
                withDocumentMutating(documentId) { textDoc ->
                    resolveAnchorRange(textDoc, anchor).setString(newText.text)
                    applyFormatting(textDoc, anchor, newText)
                }
            }
        }
    }

    private fun applyFormatting(textDoc: XTextDocument, anchor: TextAnchor, markedText: MarkedText) {
        val insertLines = markedText.text.split('\n')
        val paraCount = insertLines.size
        val paraMap = mutableMapOf<Int, Any>()
        val allParagraphs = UnoRuntime.queryInterface(XEnumerationAccess::class.java, textDoc.text).createEnumeration()
        var idx = 0
        while (allParagraphs.hasMoreElements()) {
            val para = allParagraphs.nextElement()
            val absIdx = idx++
            if (absIdx in anchor.paragraphIndex until anchor.paragraphIndex + paraCount) paraMap[absIdx] = para
        }
        // Reset formatting only on the newly inserted text.
        // In paragraph 0, the DELETE redline sits at [anchor.charStart..anchor.charEnd) — skip it first.
        // Subsequent new paragraphs start clean so their full range can be reset directly.
        val firstPara = paraMap[anchor.paragraphIndex]
        if (firstPara != null) {
            val paraStart = UnoRuntime.queryInterface(XTextRange::class.java, firstPara).start
            val resetCursor = textDoc.text.createTextCursorByRange(paraStart)
            resetCursor.goRight(anchor.charEnd.toShort(), false)
            resetCursor.goRight(insertLines[0].length.toShort(), true)
            val propSet = UnoRuntime.queryInterface(XPropertySet::class.java, resetCursor)
            propSet.setPropertyValue("CharWeight", 100f)
            propSet.setPropertyValue("CharPosture", FontSlant.NONE)
            propSet.setPropertyValue("CharUnderline", 0.toShort())
            propSet.setPropertyValue("CharStrikeout", 0.toShort())
        }
        for (paraIdx in anchor.paragraphIndex + 1 until anchor.paragraphIndex + paraCount) {
            val para = paraMap[paraIdx] ?: continue
            val range = UnoRuntime.queryInterface(XTextRange::class.java, para)
            val propSet = UnoRuntime.queryInterface(XPropertySet::class.java, textDoc.text.createTextCursorByRange(range))
            propSet.setPropertyValue("CharWeight", 100f)
            propSet.setPropertyValue("CharPosture", FontSlant.NONE)
            propSet.setPropertyValue("CharUnderline", 0.toShort())
            propSet.setPropertyValue("CharStrikeout", 0.toShort())
        }
        for (property in markedText.properties) {
            val docParaIdx = anchor.paragraphIndex + property.markIndex.paragraphIndex
            val para = paraMap[docParaIdx] ?: continue
            val paraStart = UnoRuntime.queryInterface(XTextRange::class.java, para).start
            val cursor = textDoc.text.createTextCursorByRange(paraStart)
            // For the anchor paragraph, skip past the DELETE redline before applying offsets.
            if (property.markIndex.paragraphIndex == 0) cursor.goRight(anchor.charEnd.toShort(), false)
            cursor.goRight(property.markIndex.from.toShort(), false)
            cursor.goRight((property.markIndex.to - property.markIndex.from).toShort(), true)
            val propSet = UnoRuntime.queryInterface(XPropertySet::class.java, cursor)
            when (property) {
                is BoldProperty -> propSet.setPropertyValue("CharWeight", 150f)
                is ItalicProperty -> propSet.setPropertyValue("CharPosture", FontSlant.ITALIC)
                is UnderlineProperty -> propSet.setPropertyValue("CharUnderline", 1.toShort())
                is StrikethroughProperty -> propSet.setPropertyValue("CharStrikeout", 1.toShort())
            }
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

    override suspend fun getTextByPages(documentId: String, fromPage: Int, toPage: Int, changeStatus: ChangeStatus): MarkedText =
        withContext(libreOfficeDispatcher) {
            withDocument(documentId) { textDoc ->
                val pc = pageCursorOf(textDoc)
                val vc = UnoRuntime.queryInterface(
                    XTextViewCursorSupplier::class.java,
                    UnoRuntime.queryInterface(XModel::class.java, textDoc).currentController
                ).viewCursor

                val sb = StringBuilder()
                val properties = mutableListOf<TextProperty>()
                val paragraphs = UnoRuntime.queryInterface(XEnumerationAccess::class.java, textDoc.text)
                    .createEnumeration()
                var paraIdx = 0
                while (paragraphs.hasMoreElements()) {
                    val element = paragraphs.nextElement()
                    val textRange = UnoRuntime.queryInterface(XTextRange::class.java, element)
                    vc.gotoRange(textRange.start, false)
                    val pageNum = pc.page.toInt()
                    if (pageNum in fromPage..toPage) {
                        val (text, props) = extractParagraphMarkedText(element, changeStatus, paraIdx)
                        sb.append(text).append("\n")
                        properties.addAll(props)
                    }
                    if (pageNum > toPage) break
                    paraIdx++
                }
                MarkedText(sb.toString().trimEnd(), properties)
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

    override suspend fun getComment(documentId: String, commentId: String): Comment? =
        getComments(documentId).find { it.id == commentId }

    override suspend fun updateComment(documentId: String, commentId: String, newText: String): Unit =
        withContext(libreOfficeDispatcher) {
            withDocumentMutating(documentId) { textDoc ->
                val fieldSupplier = UnoRuntime.queryInterface(XTextFieldsSupplier::class.java, textDoc)
                val fieldEnum = fieldSupplier.textFields.createEnumeration()
                while (fieldEnum.hasMoreElements()) {
                    val field = fieldEnum.nextElement()
                    val serviceInfo = UnoRuntime.queryInterface(XServiceInfo::class.java, field)
                    if (!serviceInfo.supportsService("com.sun.star.text.TextField.Annotation")) continue
                    val anchorRange = UnoRuntime.queryInterface(XTextContent::class.java, field).anchor
                    if (buildTextAnchor(textDoc, anchorRange).id == commentId) {
                        val now = java.time.LocalDateTime.now()
                        UnoRuntime.queryInterface(XPropertySet::class.java, field).apply {
                            setPropertyValue("Content", newText)
                            setPropertyValue("DateTimeValue", DateTime().apply {
                                Year = now.year.toShort()
                                Month = now.monthValue.toShort()
                                Day = now.dayOfMonth.toShort()
                                Hours = now.hour.toShort()
                                Minutes = now.minute.toShort()
                                Seconds = now.second.toShort()
                            })
                        }
                        return@withDocumentMutating
                    }
                }
                throw NoSuchElementException("Comment $commentId not found in document $documentId")
            }
        }

    override suspend fun deleteComment(documentId: String, commentId: String): Unit =
        withContext(libreOfficeDispatcher) {
            withDocumentMutating(documentId) { textDoc ->
                val fieldSupplier = UnoRuntime.queryInterface(XTextFieldsSupplier::class.java, textDoc)
                val fieldEnum = fieldSupplier.textFields.createEnumeration()
                while (fieldEnum.hasMoreElements()) {
                    val field = fieldEnum.nextElement()
                    val serviceInfo = UnoRuntime.queryInterface(XServiceInfo::class.java, field)
                    if (!serviceInfo.supportsService("com.sun.star.text.TextField.Annotation")) continue
                    val anchorRange = UnoRuntime.queryInterface(XTextContent::class.java, field).anchor
                    if (buildTextAnchor(textDoc, anchorRange).id == commentId) {
                        textDoc.text.removeTextContent(
                            UnoRuntime.queryInterface(XTextContent::class.java, field)
                        )
                        break
                    }
                }
            }
        }

    override suspend fun addComment(documentId: String, commentText: String, author: String, anchor: TextAnchor) {
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

    override suspend fun getTextByChapter(documentId: String, chapter: String, changeStatus: ChangeStatus): MarkedText =
        withContext(libreOfficeDispatcher) {
            withDocument(documentId) { textDoc ->
                val sb = StringBuilder()
                val properties = mutableListOf<TextProperty>()
                var inChapter = false
                var chapterLevel = 0
                var paraIdx = 0

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
                        val (text, props) = extractParagraphMarkedText(element, changeStatus, paraIdx)
                        sb.append(text).append("\n")
                        properties.addAll(props)
                    }
                    paraIdx++
                }
                MarkedText(sb.toString().trimEnd(), properties)
            }
        }
}
